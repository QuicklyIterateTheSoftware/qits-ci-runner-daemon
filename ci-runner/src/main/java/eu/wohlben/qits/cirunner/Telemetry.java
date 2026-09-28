package eu.wohlben.qits.cirunner;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import org.jboss.logging.Logger;
import org.jboss.logmanager.ExtLogRecord;

/**
 * The runner's own log, shipped to qits-observability as OTLP — the one place a person on the
 * platform can read a runner that lives on somebody else's machine.
 *
 * <p><b>Why a runner needs it.</b> A platform service's log reaches qits-observability over {@code
 * qits-net}; a runner on the EDGE plane is a VM whose log reached nothing but {@code docker logs} on
 * that VM. So it exports through the same public edge it dials qits-ci through, to {@code
 * observability.<domain>}, with the bearer it already mints ({@link Bearer}): the edge opens any
 * vhost to a {@code qits-platform} token, and the ingest route itself asks for no role (measured
 * live — see the README).
 *
 * <p><b>It never gets in the log's way.</b> {@link #publish} is a bounded queue's {@code offer} and
 * nothing else — no I/O on the logging thread, and a full queue drops its <em>oldest</em> line,
 * because the lines that explain a failing runner are its latest; how many were dropped is itself
 * shipped as a line. A timer on the Vert.x loop sends a batch at a time, one request in flight, and a
 * batch that fails is dropped rather than retried: a collector that is down must cost memory the
 * queue already bounds, not a backlog that grows with the outage. Lines logged before the runner
 * has a client — its first start, its registration — wait in the queue for {@link #start}.
 *
 * <p>Protobuf, because that is the one encoding qits-observability's receiver takes; hand-written
 * ({@link OtlpLogs}), because the SDK and a protobuf runtime are the wrong price for it in a static
 * binary.
 */
public final class Telemetry extends Handler {

  private static final Logger LOG = Logger.getLogger(Telemetry.class);

  /** The OTLP/HTTP path, appended to the endpoint as every OTLP exporter does. */
  static final String LOGS_PATH = "/v1/logs";

  /** A line longer than this is cut, keeping its head: a stack trace's first frames name it. */
  static final int MAX_BODY = 16 * 1024;

  /** The service name every runner's lines are bucketed under in qits-observability. */
  public static final String SERVICE_NAME = "qits-ci-runner";

  /** The OTLP instrumentation scope — this package. */
  static final String SCOPE = "eu.wohlben.qits.cirunner";

  /** How much is queued and how often and how much of it is sent. */
  public record Settings(int capacity, int batch, long intervalMillis) {
    public static Settings defaults() {
      return new Settings(4096, 512, 5_000);
    }
  }

  private final Vertx vertx;
  private final Http http;
  private final String endpoint;
  private final Map<String, String> resource;
  private final String scopeVersion;
  private final Settings settings;
  private final LongSupplier nowNanos;

  private final ArrayBlockingQueue<OtlpLogs.Entry> queue;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicBoolean inFlight = new AtomicBoolean();
  private final AtomicBoolean failing = new AtomicBoolean();
  private final SimpleFormatter julFormatter = new SimpleFormatter();

  private volatile Supplier<Future<String>> token;
  private volatile long timer = -1;

  /**
   * @param endpoint the OTLP endpoint, {@link #LOGS_PATH} appended to it; null exports nothing
   *     and queues nothing.
   */
  public Telemetry(
      Vertx vertx,
      Http http,
      String endpoint,
      Map<String, String> resource,
      String scopeVersion,
      Settings settings,
      LongSupplier nowNanos) {
    this.vertx = vertx;
    this.http = http;
    this.endpoint = endpoint;
    this.resource = Map.copyOf(resource);
    this.scopeVersion = scopeVersion;
    this.settings = settings;
    this.nowNanos = nowNanos;
    this.queue = new ArrayBlockingQueue<>(Math.max(1, settings.capacity()));
    setLevel(Level.INFO);
  }

  /** A telemetry that ships nothing: no endpoint was configured. */
  public static Telemetry off() {
    return new Telemetry(null, null, null, Map.of(), "", new Settings(1, 1, 1), System::nanoTime);
  }

  public boolean enabled() {
    return endpoint != null;
  }

  /** Where lines are posted, or null. */
  public String url() {
    return endpoint == null ? null : endpoint + LOGS_PATH;
  }

  /** Begin sending, with the runner's bearer. Idempotent; a no-op when {@link #enabled()} is not. */
  public synchronized void start(Supplier<Future<String>> token) {
    if (!enabled() || timer >= 0) {
      return;
    }
    this.token = token;
    timer = vertx.setPeriodic(settings.intervalMillis(), id -> send());
    LOG.infof("ci-runner ships its log to %s", url());
  }

  /** Stop the timer and send what is queued, waiting at most {@code waitMillis} for it. */
  public void stop(long waitMillis) {
    long t;
    synchronized (this) {
      t = timer;
      timer = -1;
    }
    if (t >= 0) {
      vertx.cancelTimer(t);
    }
    if (token == null) {
      return;
    }
    try {
      send().toCompletionStage().toCompletableFuture().get(waitMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception lastWordsLost) {
      // The process is on its way out; the container's own log still has every line.
    }
  }

  @Override
  public void publish(LogRecord record) {
    if (!enabled() || record == null || !isLoggable(record)) {
      return;
    }
    OtlpLogs.Entry entry =
        new OtlpLogs.Entry(
            record.getInstant().getEpochSecond() * 1_000_000_000L + record.getInstant().getNano(),
            OtlpLogs.severityNumber(record.getLevel().intValue()),
            severityText(record.getLevel()),
            body(record),
            record.getLoggerName());
    while (!queue.offer(entry)) {
      if (queue.poll() != null) {
        dropped.incrementAndGet();
      }
    }
  }

  /**
   * Send one batch now, if none is in flight and there is a bearer to send it with. Completes when
   * that batch is answered (or dropped); never fails.
   */
  Future<Void> send() {
    Supplier<Future<String>> bearer = token;
    if (bearer == null || !inFlight.compareAndSet(false, true)) {
      return Future.succeededFuture();
    }
    List<OtlpLogs.Entry> batch = new ArrayList<>();
    long lost = dropped.getAndSet(0);
    if (lost > 0) {
      batch.add(
          new OtlpLogs.Entry(
              nowNanos.getAsLong(),
              OtlpLogs.severityNumber(Level.WARNING.intValue()),
              "WARN",
              "ci-runner telemetry dropped " + lost + " log line(s): the queue was full",
              Telemetry.class.getName()));
    }
    queue.drainTo(batch, settings.batch() - batch.size());
    if (batch.isEmpty()) {
      inFlight.set(false);
      return Future.succeededFuture();
    }
    byte[] body = OtlpLogs.encode(resource, SCOPE, scopeVersion, batch);
    return bearer
        .get()
        .compose(
            bearerToken ->
                http.post(
                    url(),
                    Map.of(
                        "Content-Type", "application/x-protobuf",
                        "Authorization", "Bearer " + bearerToken),
                    Buffer.buffer(body)))
        .transform(
            answered -> {
              inFlight.set(false);
              String problem =
                  answered.failed()
                      ? String.valueOf(answered.cause().getMessage())
                      : answered.result().status() / 100 == 2
                          ? null
                          : "the collector answered " + answered.result().status();
              report(problem, batch.size());
              if (problem == null && queue.size() >= settings.batch()) {
                vertx.runOnContext(v -> send());
              }
              return Future.succeededFuture();
            });
  }

  /** One line when shipping starts failing and one when it recovers — never one per batch. */
  private void report(String problem, int lines) {
    if (problem != null && failing.compareAndSet(false, true)) {
      LOG.warnf(
          "ci-runner could not ship %d log line(s) to %s: %s; dropping them until it can",
          lines, url(), problem);
    } else if (problem == null && failing.compareAndSet(true, false)) {
      LOG.infof("ci-runner ships its log to %s again", url());
    }
  }

  private String body(LogRecord record) {
    String message =
        record instanceof ExtLogRecord ext
            ? ext.getFormattedMessage()
            : julFormatter.formatMessage(record);
    StringBuilder body = new StringBuilder(message == null ? "" : message);
    if (record.getThrown() != null) {
      StringWriter trace = new StringWriter();
      record.getThrown().printStackTrace(new PrintWriter(trace));
      body.append('\n').append(trace);
    }
    return body.length() <= MAX_BODY ? body.toString() : body.substring(0, MAX_BODY) + "…";
  }

  private static String severityText(Level level) {
    return switch (OtlpLogs.severityNumber(level.intValue())) {
      case 21 -> "FATAL";
      case 17 -> "ERROR";
      case 13 -> "WARN";
      case 9 -> "INFO";
      case 5 -> "DEBUG";
      default -> "TRACE";
    };
  }

  /** Nothing to do on the logging thread: the timer sends, and {@link #stop} sends the rest. */
  @Override
  public void flush() {}

  @Override
  public void close() {}
}

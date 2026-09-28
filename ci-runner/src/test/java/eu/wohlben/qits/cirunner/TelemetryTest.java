package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The log export against a real HTTP collector ({@link FakeHost}'s receiver route), decoding what
 * arrives as protobuf by the schema's field numbers ({@link OtlpDecode}).
 */
class TelemetryTest {

  private static final Map<String, String> RESOURCE =
      Map.of("service.name", "qits-ci-runner", "qits.ci.runner.id", "r1");
  private static final long NOW = 1_790_000_000_000_000_000L;

  private final Vertx vertx = Vertx.vertx();
  private FakeHost collector;
  private Http http;

  @BeforeEach
  void setUp() throws Exception {
    collector = new FakeHost(vertx);
    http = new Http(vertx, 5_000);
  }

  @AfterEach
  void tearDown() throws Exception {
    collector.close();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private Telemetry telemetry(Telemetry.Settings settings) {
    return new Telemetry(
        vertx, http, collector.base() + "/otel", RESOURCE, "1.2.3", settings, () -> NOW);
  }

  private static LogRecord line(Level level, String message) {
    LogRecord record = new LogRecord(level, message);
    record.setLoggerName("eu.wohlben.qits.cirunner.RunnerMain");
    return record;
  }

  private static void await(Future<?> future) throws Exception {
    future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void linesLoggedBeforeTheBearerAreShippedOnceItArrivesWithItAsAuthorization() throws Exception {
    Telemetry telemetry = telemetry(new Telemetry.Settings(100, 50, 60_000));
    telemetry.publish(line(Level.INFO, "ci-runner registered as r1"));
    telemetry.publish(line(Level.WARNING, "ci-runner could not remove x"));
    await(telemetry.send());
    assertEquals(List.of(), collector.telemetryBodies, "nothing is sent without a bearer");

    telemetry.start(() -> Future.succeededFuture("runner-access-token"));
    await(telemetry.send());
    telemetry.stop(1_000);

    assertEquals(List.of("Bearer runner-access-token"), collector.telemetryAuthorizations);
    OtlpDecode.Request request = OtlpDecode.decode(collector.telemetryBodies.getFirst());
    assertEquals(RESOURCE, request.resource());
    assertEquals(Telemetry.SCOPE, request.scopeName());
    assertEquals("1.2.3", request.scopeVersion());
    assertEquals(2, request.lines().size());
    OtlpDecode.Line first = request.lines().getFirst();
    assertEquals("ci-runner registered as r1", first.body());
    assertEquals("INFO", first.severityText());
    assertEquals(9, first.severityNumber());
    assertEquals(first.timeUnixNano(), first.observedTimeUnixNano());
    assertTrue(first.timeUnixNano() > 0);
    assertEquals(
        Map.of(OtlpLogs.LOGGER_ATTRIBUTE, "eu.wohlben.qits.cirunner.RunnerMain"),
        first.attributes());
    OtlpDecode.Line second = request.lines().get(1);
    assertEquals("WARN", second.severityText());
    assertEquals(13, second.severityNumber());
  }

  @Test
  void aJbossPrintfLineIsShippedFormattedWithItsStackTrace() throws Exception {
    Telemetry telemetry = telemetry(new Telemetry.Settings(100, 50, 60_000));
    ExtLogRecord record =
        new ExtLogRecord(
            org.jboss.logmanager.Level.ERROR,
            "ci-runner could not launch run %s step %d",
            ExtLogRecord.FormatStyle.PRINTF,
            Telemetry.class.getName());
    record.setParameters(new Object[] {"run-1", 2});
    record.setThrown(new IllegalStateException("boom"));
    telemetry.publish(record);
    telemetry.start(() -> Future.succeededFuture("t"));
    await(telemetry.send());

    OtlpDecode.Line line =
        OtlpDecode.decode(collector.telemetryBodies.getFirst()).lines().getFirst();
    assertTrue(line.body().startsWith("ci-runner could not launch run run-1 step 2\n"), line.body());
    assertTrue(line.body().contains("IllegalStateException: boom"), line.body());
    assertEquals("ERROR", line.severityText());
    assertEquals(17, line.severityNumber());
  }

  @Test
  void debugLinesAreNotShipped() throws Exception {
    Telemetry telemetry = telemetry(new Telemetry.Settings(100, 50, 60_000));
    telemetry.publish(line(Level.FINE, "chatter"));
    telemetry.publish(line(Level.INFO, "kept"));
    telemetry.start(() -> Future.succeededFuture("t"));
    await(telemetry.send());
    List<OtlpDecode.Line> lines = OtlpDecode.decode(collector.telemetryBodies.getFirst()).lines();
    assertEquals(List.of("kept"), lines.stream().map(OtlpDecode.Line::body).toList());
  }

  @Test
  void aFullQueueDropsItsOldestLinesAndSaysHowMany() throws Exception {
    Telemetry telemetry = telemetry(new Telemetry.Settings(3, 50, 60_000));
    for (int i = 0; i < 5; i++) {
      telemetry.publish(line(Level.INFO, "line " + i));
    }
    telemetry.start(() -> Future.succeededFuture("t"));
    await(telemetry.send());
    List<String> bodies =
        OtlpDecode.decode(collector.telemetryBodies.getFirst()).lines().stream()
            .map(OtlpDecode.Line::body)
            .toList();
    assertEquals(
        List.of(
            "ci-runner telemetry dropped 2 log line(s): the queue was full",
            "line 2",
            "line 3",
            "line 4"),
        bodies,
        "the newest lines survive; the loss is itself a line");
  }

  @Test
  void fullBatchesFollowWithoutWaitingForTheTimerAndStopShipsTheRest() throws Exception {
    Telemetry telemetry = telemetry(new Telemetry.Settings(100, 2, 60_000));
    for (int i = 0; i < 5; i++) {
      telemetry.publish(line(Level.INFO, "line " + i));
    }
    telemetry.start(() -> Future.succeededFuture("t"));
    await(telemetry.send());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (collector.telemetryBodies.size() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    Thread.sleep(200);
    assertEquals(2, collector.telemetryBodies.size(), "a part batch waits for the timer");

    telemetry.stop(5_000);
    assertEquals(
        List.of(2, 2, 1),
        collector.telemetryBodies.stream()
            .map(b -> OtlpDecode.decode(b).lines().size())
            .toList());
  }

  @Test
  void theTimerShipsOnItsOwn() throws Exception {
    Telemetry telemetry = telemetry(new Telemetry.Settings(100, 50, 50));
    telemetry.start(() -> Future.succeededFuture("t"));
    telemetry.publish(line(Level.INFO, "by the timer"));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (collector.telemetryBodies.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    telemetry.stop(1_000);
    assertEquals(
        "by the timer",
        OtlpDecode.decode(collector.telemetryBodies.getFirst()).lines().getFirst().body());
  }

  @Test
  void aRefusingCollectorOrAFailedMintDropsTheBatchAndNeverThrows() throws Exception {
    collector.telemetryStatus = 401;
    Telemetry telemetry = telemetry(new Telemetry.Settings(100, 50, 60_000));
    AtomicInteger mints = new AtomicInteger();
    telemetry.start(
        () ->
            mints.incrementAndGet() == 1
                ? Future.succeededFuture("t")
                : Future.failedFuture("the token endpoint answered 503"));
    telemetry.publish(line(Level.INFO, "refused"));
    await(telemetry.send());
    assertEquals(1, collector.telemetryBodies.size());

    telemetry.publish(line(Level.INFO, "no bearer"));
    await(telemetry.send());
    assertEquals(1, collector.telemetryBodies.size(), "a failed mint sends nothing");

    collector.telemetryStatus = 200;
    telemetry.start(() -> Future.succeededFuture("t")); // already started: a no-op
    mints.set(0);
    telemetry.publish(line(Level.INFO, "after"));
    await(telemetry.send());
    List<String> last =
        OtlpDecode.decode(collector.telemetryBodies.getLast()).lines().stream()
            .map(OtlpDecode.Line::body)
            .toList();
    assertFalse(last.contains("refused"), "a failed batch is dropped, not retried");
    assertFalse(last.contains("no bearer"));
    assertTrue(last.contains("after"), last::toString);
  }

  @Test
  void offShipsNothingAndQueuesNothing() throws Exception {
    Telemetry off = Telemetry.off();
    assertFalse(off.enabled());
    assertNull(off.url());
    off.publish(line(Level.SEVERE, "nowhere"));
    off.start(() -> Future.succeededFuture("t"));
    await(off.send());
    off.stop(100);
    assertEquals(List.of(), collector.telemetryBodies);
  }

  @Test
  void theSeverityBandsFollowOtel() {
    assertEquals(21, OtlpLogs.severityNumber(org.jboss.logmanager.Level.FATAL.intValue()));
    assertEquals(17, OtlpLogs.severityNumber(Level.SEVERE.intValue()));
    assertEquals(13, OtlpLogs.severityNumber(Level.WARNING.intValue()));
    assertEquals(9, OtlpLogs.severityNumber(Level.INFO.intValue()));
    assertEquals(5, OtlpLogs.severityNumber(Level.FINE.intValue()));
    assertEquals(1, OtlpLogs.severityNumber(Level.FINEST.intValue()));
  }
}

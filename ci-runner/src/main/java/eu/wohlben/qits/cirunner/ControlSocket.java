package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.CiRunnerCodec;
import eu.wohlben.qits.cirunner.protocol.CiRunnerDecodeException;
import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol.CloseReason;
import eu.wohlben.qits.cirunner.protocol.Heartbeat;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * The runner's one connection: an outbound WebSocket to qits-ci's {@code socketUrl}, with the
 * client's bearer on the upgrade. Nothing listens on the runner host — no inbound port, which is the
 * property that lets a runner live behind any NAT a person's VM happens to sit behind.
 *
 * <p>qits-ci-daemon's {@code ControlSocket} in its mechanics — vert.x {@link WebSocketClient}, capped
 * backoff, writes marshalled onto the connection's context — and the qits-workspace-daemon's in its
 * one invariant: <b>it reconnects forever</b>. A runner is its host's reason to be on the platform,
 * and the only right response to a lost socket is to get it back; 500 ms doubling to a 30 s cap, reset
 * by a connection that succeeds. Every reconnect mints its bearer first, so a token that expired
 * while the socket was up is replaced rather than presented.
 *
 * <p><b>Forever ends at one thing: the platform saying this runner no longer exists.</b> A runner
 * deleted while it was offline is never sent its {@code Retire}; it finds out on its next dial, and
 * would otherwise redial for as long as its host is up. Two answers say it, and only two:
 *
 * <ul>
 *   <li>qits-ci closes the socket 1008 {@link CloseReason#RUNNER_DELETED} — the host's own word that
 *       the bearer is valid and no runner is registered with it. Believed at once.
 *   <li>the token endpoint refuses the client with {@code invalid_client} ({@link
 *       Bearer.ClientRefused}) — what a deleted runner's revoked client gets. Believed only as a
 *       streak: every dial for at least {@link Settings#refusalConfirmMillis} and at least {@link
 *       Settings#refusalConfirmAttempts} dials, with nothing else in between, since one such answer
 *       could be an idp that is not ready yet.
 * </ul>
 *
 * Everything else — a 502 from the edge, a refused connection, a timeout, a 401 on the upgrade, a
 * 5xx from the idp, any other close — is the platform being down or restarting, breaks a streak, and
 * is retried as it always was. On either answer the socket stops for good and tells its listener
 * {@link Listener#onDeleted}.
 */
public final class ControlSocket {

  private static final Logger LOG = Logger.getLogger(ControlSocket.class);

  /** What the socket tells its owner. */
  public interface Listener {

    /** A session began. Say {@code Hello}. Called off the event loop is not guaranteed. */
    void onConnected();

    /** A decodable frame arrived. */
    void onMessage(CiRunnerMessage message);

    /** The session ended. The socket is already scheduling its own reconnect. */
    void onClosed();

    /**
     * The platform said this runner no longer exists (see the class javadoc). The socket has stopped
     * for good and will not redial; {@code why} is for the log.
     */
    default void onDeleted(String why) {}
  }

  /**
   * Liveness and reconnect knobs, and how long a streak of {@code invalid_client} refusals must last
   * before it is believed.
   */
  public record Settings(
      long heartbeatMillis,
      long initialBackoffMillis,
      long maxBackoffMillis,
      long refusalConfirmMillis,
      int refusalConfirmAttempts) {

    /** Five minutes and five dials: longer than any idp restart, short of a runner that lingers. */
    public static final long DEFAULT_REFUSAL_CONFIRM_MILLIS = 300_000;

    public static final int DEFAULT_REFUSAL_CONFIRM_ATTEMPTS = 5;

    public Settings(long heartbeatMillis, long initialBackoffMillis, long maxBackoffMillis) {
      this(
          heartbeatMillis,
          initialBackoffMillis,
          maxBackoffMillis,
          DEFAULT_REFUSAL_CONFIRM_MILLIS,
          DEFAULT_REFUSAL_CONFIRM_ATTEMPTS);
    }
  }

  private final Vertx vertx;
  private final String url;
  private final Supplier<Future<String>> bearer;
  private final Settings settings;
  private final Listener listener;

  private volatile WebSocketClient client;
  private volatile WebSocket socket;
  private volatile Context socketContext;
  private volatile boolean stopped;
  private long heartbeatTimer = -1;

  /** The {@code invalid_client} streak: when it began (0 for none) and how many dials it holds. */
  private long refusedSinceMillis;

  private int refusals;

  public ControlSocket(
      Vertx vertx,
      String url,
      Supplier<Future<String>> bearer,
      Settings settings,
      Listener listener) {
    this.vertx = vertx;
    this.url = url;
    this.bearer = bearer;
    this.settings = settings;
    this.listener = listener;
  }

  /** Begin dialling. Returns at once; sessions are reported to the listener. */
  public void start() {
    client = vertx.createWebSocketClient();
    if (settings.heartbeatMillis() > 0) {
      heartbeatTimer = vertx.setPeriodic(settings.heartbeatMillis(), id -> heartbeat());
    }
    connect(0);
  }

  private void connect(int attempt) {
    if (stopped) {
      return;
    }
    WebSocketConnectOptions options = optionsFor(url);
    bearer
        .get()
        .compose(
            token -> {
              options.putHeader("Authorization", "Bearer " + token);
              return client.connect(options);
            })
        .onSuccess(this::onConnected)
        .onFailure(
            t -> {
              if (confirmsDeletion(t)) {
                return;
              }
              long backoff = backoffFor(attempt);
              LOG.warnf(
                  "ci-runner could not connect to %s (attempt %d): %s; retrying in %dms",
                  url, attempt + 1, t.getMessage(), backoff);
              vertx.setTimer(backoff, id -> connect(attempt + 1));
            });
  }

  /**
   * Count a failed dial toward the {@code invalid_client} streak, or break the streak; true when the
   * streak is now long enough to believe, in which case the socket has stopped and the listener has
   * been told.
   */
  private boolean confirmsDeletion(Throwable failure) {
    if (!(failure instanceof Bearer.ClientRefused refused)) {
      refusedSinceMillis = 0;
      refusals = 0;
      return false;
    }
    long now = System.currentTimeMillis();
    if (refusals == 0) {
      refusedSinceMillis = now;
    }
    refusals++;
    long lasted = now - refusedSinceMillis;
    if (refusals < settings.refusalConfirmAttempts() || lasted < settings.refusalConfirmMillis()) {
      LOG.warnf(
          "ci-runner's client was refused (%s), %d time(s) in a row over %ds; if it goes on for"
              + " %ds this runner reads itself as deleted",
          refused.getMessage(), refusals, lasted / 1000, settings.refusalConfirmMillis() / 1000);
      return false;
    }
    deleted(
        "the token endpoint has refused this runner's client for "
            + lasted / 1000
            + "s ("
            + refusals
            + " dials): "
            + refused.getMessage());
    return true;
  }

  /** The platform said this runner is gone: stop for good, then say so. Once. */
  private void deleted(String why) {
    if (stopped) {
      return;
    }
    stop();
    listener.onDeleted(why);
  }

  long backoffFor(int attempt) {
    long backoff = settings.initialBackoffMillis() * (1L << Math.min(attempt, 20));
    return Math.min(settings.maxBackoffMillis(), Math.max(1, backoff));
  }

  private void onConnected(WebSocket ws) {
    refusedSinceMillis = 0;
    refusals = 0;
    socketContext = vertx.getOrCreateContext();
    ws.textMessageHandler(this::onFrame);
    ws.exceptionHandler(t -> LOG.debugf("ci-runner control socket error: %s", t.getMessage()));
    ws.closeHandler(
        v -> {
          socket = null;
          listener.onClosed();
          if (isDeletedClose(ws.closeStatusCode(), ws.closeReason())) {
            LOG.warnf("ci-runner's socket was closed %s by the host", CloseReason.RUNNER_DELETED);
            deleted("the host closed the socket " + CloseReason.RUNNER_DELETED);
            return;
          }
          if (!stopped) {
            LOG.warnf("ci-runner lost its connection to %s; reconnecting", url);
            vertx.setTimer(settings.initialBackoffMillis(), id -> connect(0));
          }
        });
    socket = ws;
    listener.onConnected();
  }

  private void onFrame(String json) {
    CiRunnerMessage message;
    try {
      message = CiRunnerCodec.decode(new JsonObject(json).getMap());
    } catch (CiRunnerDecodeException e) {
      // Dropped, not fatal: a host one capability ahead sends frames this binary cannot know, and
      // a runner that died of them would take every run it holds with it.
      LOG.warnf("ci-runner dropped a frame it does not understand (%s): %s", e.reason(), e.getMessage());
      return;
    } catch (RuntimeException e) {
      LOG.warnf("ci-runner dropped an undecodable frame: %s", e.getMessage());
      return;
    }
    listener.onMessage(message);
  }

  /** Write a frame, marshalled onto the connection's context; fails when there is no session. */
  public Future<Void> send(CiRunnerMessage message) {
    WebSocket ws = socket;
    if (ws == null || ws.isClosed()) {
      return Future.failedFuture("ci-runner control socket is not open");
    }
    String json = new JsonObject(CiRunnerCodec.encode(message)).encode();
    Context context = socketContext;
    if (context != null && Vertx.currentContext() != context) {
      Promise<Void> promise = Promise.promise();
      context.runOnContext(v -> ws.writeTextMessage(json).onComplete(promise));
      return promise.future();
    }
    return ws.writeTextMessage(json);
  }

  /** End for good: no reconnect after this. */
  public Future<Void> stop() {
    stopped = true;
    if (heartbeatTimer >= 0) {
      vertx.cancelTimer(heartbeatTimer);
    }
    WebSocket ws = socket;
    Future<Void> closed = ws == null || ws.isClosed() ? Future.succeededFuture() : ws.close();
    return closed.otherwiseEmpty().onComplete(v -> {
      WebSocketClient c = client;
      if (c != null) {
        c.close();
      }
    });
  }

  private void heartbeat() {
    WebSocket ws = socket;
    if (ws != null && !ws.isClosed()) {
      send(new Heartbeat());
    }
  }

  /** 1008 {@link CloseReason#RUNNER_DELETED}: the host's word that this runner does not exist. */
  static boolean isDeletedClose(Short status, String reason) {
    return status != null && status == 1008 && CloseReason.RUNNER_DELETED.equals(reason);
  }

  /**
   * The url as vert.x wants it, path and query carried across untouched. {@code http(s)} is read as
   * {@code ws(s)}, so a register door that answers the service's base url still dials.
   */
  static WebSocketConnectOptions optionsFor(String url) {
    URI uri = URI.create(url);
    if (uri.getHost() == null) {
      throw new IllegalArgumentException("no host in '" + url + "'");
    }
    String scheme = uri.getScheme() == null ? "ws" : uri.getScheme().toLowerCase();
    boolean ssl = scheme.equals("wss") || scheme.equals("https");
    int port = uri.getPort() != -1 ? uri.getPort() : (ssl ? 443 : 80);
    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    if (uri.getRawQuery() != null) {
      path = path + "?" + uri.getRawQuery();
    }
    return new WebSocketConnectOptions().setHost(uri.getHost()).setPort(port).setURI(path).setSsl(ssl);
  }
}

package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.CiRunnerCodec;
import eu.wohlben.qits.cirunner.protocol.CiRunnerDecodeException;
import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
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
  }

  /** Liveness and reconnect knobs. */
  public record Settings(long heartbeatMillis, long initialBackoffMillis, long maxBackoffMillis) {}

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
              long backoff = backoffFor(attempt);
              LOG.warnf(
                  "ci-runner could not connect to %s (attempt %d): %s; retrying in %dms",
                  url, attempt + 1, t.getMessage(), backoff);
              vertx.setTimer(backoff, id -> connect(attempt + 1));
            });
  }

  long backoffFor(int attempt) {
    long backoff = settings.initialBackoffMillis() * (1L << Math.min(attempt, 20));
    return Math.min(settings.maxBackoffMillis(), Math.max(1, backoff));
  }

  private void onConnected(WebSocket ws) {
    socketContext = vertx.getOrCreateContext();
    ws.textMessageHandler(this::onFrame);
    ws.exceptionHandler(t -> LOG.debugf("ci-runner control socket error: %s", t.getMessage()));
    ws.closeHandler(
        v -> {
          socket = null;
          listener.onClosed();
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

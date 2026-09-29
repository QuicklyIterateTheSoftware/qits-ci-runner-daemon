package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.CiRunnerCodec;
import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * qits-ci's side, on one real in-JVM Vert.x server: the register door, the idp's token endpoint and
 * the runner control socket, each with its half of the conversation scripted by the test.
 */
final class FakeHost implements AutoCloseable {

  final HttpServer server;
  final List<CiRunnerMessage> received = Collections.synchronizedList(new ArrayList<>());
  final List<String> registerBodies = Collections.synchronizedList(new ArrayList<>());
  final List<String> registerAuthorizations = Collections.synchronizedList(new ArrayList<>());
  final List<String> tokenBodies = Collections.synchronizedList(new ArrayList<>());
  /** The Authorization header each token request carried, {@code null} for none. */
  final List<String> tokenAuthorizations = Collections.synchronizedList(new ArrayList<>());
  final AtomicInteger upgrades = new AtomicInteger();
  /** Each log export's body and its Authorization header, in arrival order. */
  final List<byte[]> telemetryBodies = Collections.synchronizedList(new ArrayList<>());
  final List<String> telemetryAuthorizations = Collections.synchronizedList(new ArrayList<>());
  volatile int telemetryStatus = 200;

  volatile int registerStatus = 200;
  volatile String registerBody;
  volatile String accessToken = "runner-access-token";
  volatile long expiresIn = 300;
  /** When not 200, the token endpoint answers this status with {@link #tokenErrorBody}. */
  volatile int tokenStatus = 200;
  volatile String tokenErrorBody = "";
  volatile MultiMap upgradeHeaders;
  volatile ServerWebSocket socket;
  volatile BiConsumer<FakeHost, CiRunnerMessage> script = (h, m) -> {};

  private final List<CompletableFuture<ServerWebSocket>> sessions =
      Collections.synchronizedList(new ArrayList<>());

  FakeHost(Vertx vertx) throws Exception {
    this.server =
        vertx
            .createHttpServer()
            .requestHandler(this::onRequest)
            .webSocketHandler(this::onUpgrade)
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
  }

  String base() {
    return "http://127.0.0.1:" + server.actualPort();
  }

  /** The client JSON the register door answers with by default. */
  String clientJson() {
    return new JsonObject()
        .put("clientId", "ci-runner-r1")
        .put("secret", "client-secret")
        .put("tokenUrl", base() + "/token")
        .put("audience", "qits-ci")
        .put("socketUrl", "ws://127.0.0.1:" + server.actualPort() + "/ci/runner")
        .encode();
  }

  private void onRequest(HttpServerRequest request) {
    request
        .body()
        .onSuccess(
            body -> {
              if (request.path().startsWith("/ci/api/runners/") && request.path().endsWith("/register")) {
                registerBodies.add(body.toString());
                registerAuthorizations.add(request.getHeader("Authorization"));
                request
                    .response()
                    .setStatusCode(registerStatus)
                    .end(registerBody != null ? registerBody : clientJson());
              } else if (request.path().equals("/token")) {
                tokenBodies.add(body.toString());
                tokenAuthorizations.add(request.getHeader("Authorization"));
                if (request.getHeader("Authorization") != null) {
                  // What the live edge answers: it consumes an Authorization header as its own
                  // credential, so a client that authenticates with one reaches the idp as nobody.
                  request
                      .response()
                      .setStatusCode(401)
                      .end("{\"error\":\"client authentication is required\"}");
                  return;
                }
                if (tokenStatus != 200) {
                  request.response().setStatusCode(tokenStatus).end(tokenErrorBody);
                  return;
                }
                request
                    .response()
                    .end(
                        new JsonObject()
                            .put("access_token", accessToken)
                            .put("expires_in", expiresIn)
                            .encode());
              } else if (request.path().equals("/otel" + Telemetry.LOGS_PATH)) {
                // qits-observability's receiver, as the edge forwards it.
                telemetryBodies.add(body.getBytes());
                telemetryAuthorizations.add(request.getHeader("Authorization"));
                request.response().setStatusCode(telemetryStatus).end();
              } else {
                request.response().setStatusCode(404).end();
              }
            });
  }

  private void onUpgrade(ServerWebSocket ws) {
    upgrades.incrementAndGet();
    upgradeHeaders = ws.headers();
    socket = ws;
    ws.textMessageHandler(
        json -> {
          CiRunnerMessage message = CiRunnerCodec.decode(new JsonObject(json).getMap());
          received.add(message);
          script.accept(this, message);
        });
    synchronized (sessions) {
      for (CompletableFuture<ServerWebSocket> waiting : sessions) {
        waiting.complete(ws);
      }
      sessions.clear();
    }
  }

  /** A frame as text, bypassing the codec — what a host a version ahead of the runner sends. */
  void sendRaw(String json) {
    socket.writeTextMessage(json);
  }

  /** Close the current socket the way qits-ci does a refusal: a code and a reason. */
  void closeSocket(int code, String reason) {
    socket.close((short) code, reason);
  }

  void send(CiRunnerMessage message) {
    socket.writeTextMessage(new JsonObject(CiRunnerCodec.encode(message)).encode());
  }

  /** Wait until {@code count} frames of {@code type} have arrived; returns the last. */
  <T extends CiRunnerMessage> T await(Class<T> type, int count) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      List<T> matching = all(type);
      if (matching.size() >= count) {
        return matching.get(count - 1);
      }
      Thread.sleep(20);
    }
    throw new AssertionError("expected " + count + " " + type.getSimpleName() + ", saw " + received);
  }

  <T extends CiRunnerMessage> T await(Class<T> type) throws Exception {
    return await(type, 1);
  }

  <T extends CiRunnerMessage> List<T> all(Class<T> type) {
    synchronized (received) {
      return received.stream().filter(type::isInstance).map(type::cast).toList();
    }
  }

  @Override
  public void close() throws Exception {
    server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }
}

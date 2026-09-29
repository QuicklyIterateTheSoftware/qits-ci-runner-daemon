package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** client_credentials, in memory, refreshed before it runs out — and not a moment sooner. */
class BearerTest {

  private final Vertx vertx = Vertx.vertx();

  @AfterEach
  void tearDown() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void aTokenIsMintedWithTheClientsPairAndAudienceThenReusedUntilNearExpiry() throws Exception {
    try (FakeHost host = new FakeHost(vertx)) {
      host.expiresIn = 300;
      AtomicLong now = new AtomicLong(1_000_000);
      ClientCredentials client =
          new ClientCredentials(
              "ci-runner-r1", "client-secret", host.base() + "/token", "qits-ci",
              "ws://127.0.0.1:1/ci/runner", "");
      Bearer bearer = new Bearer(new Http(vertx, 5_000), client, now::get);

      assertEquals("runner-access-token", get(bearer));
      // client_secret_post: the pair is in the form body, and no Authorization header is sent —
      // the edge a remote runner mints through consumes one, and the idp then sees no client.
      assertEquals(
          "grant_type=client_credentials&client_id=ci-runner-r1&client_secret=client-secret"
              + "&audience=qits-ci",
          host.tokenBodies.getFirst());
      assertNull(host.tokenAuthorizations.getFirst(), "no Authorization header");

      now.addAndGet(239_000); // 300s lifetime, 60s margin: still good at 239s
      assertEquals("runner-access-token", get(bearer));
      assertEquals(1, host.tokenBodies.size());

      host.accessToken = "refreshed";
      now.addAndGet(2_000); // 241s: inside the margin
      assertEquals("refreshed", get(bearer));
      assertEquals(2, host.tokenBodies.size());
    }
  }

  @Test
  void aShortLivedTokenRefreshesAtAFifthOfItsLifeRatherThanNever() throws Exception {
    try (FakeHost host = new FakeHost(vertx)) {
      host.expiresIn = 50; // a 60s margin would make it stale on arrival
      AtomicLong now = new AtomicLong(0);
      Bearer bearer =
          new Bearer(
              new Http(vertx, 5_000),
              new ClientCredentials(
                  "c", "s", host.base() + "/token", "", "ws://127.0.0.1:1/x", ""),
              now::get);
      get(bearer);
      now.set(39_000);
      get(bearer);
      assertEquals(1, host.tokenBodies.size());
      now.set(41_000);
      get(bearer);
      assertEquals(2, host.tokenBodies.size());
      assertEquals(
          "grant_type=client_credentials&client_id=c&client_secret=s",
          host.tokenBodies.getFirst(),
          "no audience, none sent");
    }
  }

  @Test
  void aPairOutsideTheUnreservedSetIsFormEncodedInTheBody() throws Exception {
    try (FakeHost host = new FakeHost(vertx)) {
      Bearer bearer =
          new Bearer(
              new Http(vertx, 5_000),
              new ClientCredentials(
                  "ci runner", "s3cr3t+/=&x", host.base() + "/token", "qits-platform",
                  "ws://127.0.0.1:1/x", ""),
              () -> 0L);
      get(bearer);
      assertEquals(
          "grant_type=client_credentials&client_id=ci+runner&client_secret=s3cr3t%2B%2F%3D%26x"
              + "&audience=qits-platform",
          host.tokenBodies.getFirst());
    }
  }

  private static String get(Bearer bearer) throws Exception {
    return bearer.token().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void onlyAnOauthInvalidClientOnA400Or401IsAClientRefusal() {
    assertTrue(Bearer.refusesTheClient(401, "{\"error\":\"invalid_client\"}"));
    assertTrue(
        Bearer.refusesTheClient(
            400, "{\"error\":\"invalid_client\",\"error_description\":\"client authentication failed\"}"));
    // The edge's own 401, an idp that is down, a gateway: the platform, not the runner's client.
    assertFalse(Bearer.refusesTheClient(401, "{\"error\":\"client authentication is required\"}"));
    assertFalse(Bearer.refusesTheClient(401, "<html>Unauthorized</html>"));
    assertFalse(Bearer.refusesTheClient(401, ""));
    assertFalse(Bearer.refusesTheClient(502, "{\"error\":\"invalid_client\"}"));
    assertFalse(Bearer.refusesTheClient(503, "Service Unavailable"));
    assertFalse(Bearer.refusesTheClient(400, "{\"error\":\"invalid_grant\"}"));
  }

  @Test
  void aRefusedClientFailsTheMintWithItsOwnTypeAndAnyOtherRefusalDoesNot() throws Exception {
    try (FakeHost host = new FakeHost(vertx)) {
      ClientCredentials client =
          new ClientCredentials(
              "ci-runner-r1", "client-secret", host.base() + "/token", "", "ws://127.0.0.1:1/x", "");
      Bearer bearer = new Bearer(new Http(vertx, 5_000), client, System::currentTimeMillis);

      host.tokenStatus = 401;
      host.tokenErrorBody = "{\"error\":\"invalid_client\"}";
      Throwable refused = failure(bearer);
      assertTrue(refused instanceof Bearer.ClientRefused, refused::toString);

      host.tokenStatus = 502;
      host.tokenErrorBody = "Bad Gateway";
      Throwable transientFailure = failure(bearer);
      assertFalse(transientFailure instanceof Bearer.ClientRefused, transientFailure::toString);
    }
  }

  private static Throwable failure(Bearer bearer) throws Exception {
    java.util.concurrent.CompletableFuture<Throwable> failed = new java.util.concurrent.CompletableFuture<>();
    bearer.token().onComplete(r -> failed.complete(r.failed() ? r.cause() : null));
    Throwable cause = failed.get(10, TimeUnit.SECONDS);
    assertTrue(cause != null, "the mint failed");
    return cause;
  }
}

package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import java.util.Base64;
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
      String basic =
          Base64.getEncoder().encodeToString("ci-runner-r1:client-secret".getBytes());
      assertEquals(
          "Basic " + basic + " grant_type=client_credentials&audience=qits-ci",
          host.tokenBodies.getFirst());

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
      assertTrue(host.tokenBodies.getFirst().endsWith("grant_type=client_credentials"), "no audience, none sent");
    }
  }

  private static String get(Bearer bearer) throws Exception {
    return bearer.token().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }
}

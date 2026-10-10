package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import io.vertx.core.Vertx;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The runner's token mint at qits-idp (qits-1149): {@code client_credentials} with the pair in the
 * form body, built from qits-idp's golden masters. The runner reads the token and its lifetime, and
 * the OAuth {@code error} of a refusal.
 */
class IdpServicePactTest {

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  /** Every control-socket dial (and the log export) mints through {@link Bearer#token()}. */
  static final Trigger DIAL = Trigger.event("the control socket dial");

  static final GoldenInteraction MINT =
      GoldenInteraction.of(DIAL, "a commissioned client", "issueToken")
          .consumes("access_token", "expires_in");

  static final GoldenInteraction UNKNOWN =
      GoldenInteraction.of(DIAL, "no commissioned client with the given id", "issueToken")
          .consumes("error");

  static final ConsumerPact PACT = ConsumerPact.of("qits-ci-runner-daemon", IDP, MINT, UNKNOWN);

  private final Vertx vertx = Vertx.vertx();

  @AfterEach
  void tearDown() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private String mint(String url, Map<String, String> params) throws Exception {
    ClientCredentials client =
        new ClientCredentials(
            params.get("clientId"),
            params.get("secret"),
            url + "/idp/token",
            params.get("audience"),
            "wss://ci.qits.suite.example/ci/runners/socket",
            "");
    Bearer bearer = new Bearer(new Http(vertx, 5_000), client, System::currentTimeMillis);
    return bearer.token().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void aCommissionedClientGetsAToken() {
    PACT.run(MINT, (url, recorded) -> assertEquals("opaque", mint(url, recorded.params())));
  }

  @Test
  void anUnknownClientIsRefusedAsInvalidClient() {
    PACT.run(
        UNKNOWN,
        (url, recorded) -> {
          ExecutionException failed =
              org.junit.jupiter.api.Assertions.assertThrows(
                  ExecutionException.class, () -> mint(url, recorded.params()));
          assertInstanceOf(Bearer.ClientRefused.class, failed.getCause());
        });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.assertEveryInteractionCarriesBothReferences();
    PACT.compareOrWritePactFile();
  }
}

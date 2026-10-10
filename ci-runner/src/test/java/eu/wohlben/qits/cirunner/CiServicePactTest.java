package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import io.vertx.core.Vertx;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runner's one call to qits-ci (qits-1149): the register door, built from qits-ci's golden
 * masters. The runner reads the client out of a 200, and only the status of a refusal.
 */
class CiServicePactTest {

  static final GoldenMasters CI = GoldenMasters.of("qits-ci-service", "qits-ci");

  /** The runner registers when it starts with a registration token and no client for it. */
  static final Trigger START = Trigger.event("StartupEvent");

  static final GoldenInteraction REGISTER =
      GoldenInteraction.of(START, "an unregistered runner", "registerRunner")
          .header("Authorization", "{authorization}")
          .consumes("clientId", "secret", "tokenUrl", "audience", "socketUrl");

  static final GoldenInteraction REGISTERED =
      GoldenInteraction.of(START, "a registered runner", "registerRunner")
          .header("Authorization", "{authorization}");

  static final ConsumerPact PACT = ConsumerPact.of("qits-ci-runner-daemon", CI, REGISTER, REGISTERED);

  /**
   * The capabilities qits-ci recorded: docker and arch. A request body may not carry keys the pact
   * does not hold, so this runner has no os and no labels, which it then leaves out.
   */
  private static final Capabilities CAPS = new Capabilities(true, "amd64", null, Map.of());

  private final Vertx vertx = Vertx.vertx();

  @TempDir Path state;

  @AfterEach
  void tearDown() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private ClientCredentials register(String url, Map<String, String> params) throws Exception {
    String token = params.get("authorization").substring("Bearer ".length());
    RunnerEnv env =
        RunnerEnv.parse(url, params.get("runnerId"), token, state.toString(), "2", null, null, null);
    return new Registration(new Http(vertx, 5_000), 5_000).ensure(env, CAPS);
  }

  @Test
  void anUnregisteredRunnerGetsItsClient() {
    PACT.run(
        REGISTER,
        (url, recorded) -> {
          ClientCredentials client = register(url, recorded.params());
          assertEquals("run-client-1", client.clientId());
          assertEquals("run-s3cr3t-1", client.secret());
          assertEquals("https://idp.qits.suite.example/idp/token", client.tokenUrl());
          assertEquals("qits-platform", client.audience());
          assertEquals("wss://ci.qits.suite.example/ci/runners/socket", client.socketUrl());
        });
  }

  @Test
  void aRegisteredRunnerIsRefused() {
    PACT.run(
        REGISTERED,
        (url, recorded) -> {
          Registration.Failed refused =
              assertThrows(Registration.Failed.class, () -> register(url, recorded.params()));
          assertEquals(ExitCode.REGISTRATION_REFUSED, refused.exitCode());
        });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.assertEveryInteractionCarriesBothReferences();
    PACT.compareOrWritePactFile();
  }
}

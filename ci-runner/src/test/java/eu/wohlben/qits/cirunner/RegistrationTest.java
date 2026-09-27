package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cirunner.protocol.Capabilities;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The one-time token becomes a client on disk, once, and the token is never presented again. */
@EnabledOnOs(OS.LINUX)
class RegistrationTest {

  private final Vertx vertx = Vertx.vertx();
  private FakeHost host;
  private Http http;

  @TempDir Path state;

  private static final Capabilities CAPS =
      new Capabilities(true, "amd64", "linux", Map.of("site", "home"));

  @BeforeEach
  void setUp() throws Exception {
    host = new FakeHost(vertx);
    http = new Http(vertx, 5_000);
  }

  @AfterEach
  void tearDown() throws Exception {
    host.close();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private RunnerEnv env(String token) throws Exception {
    return RunnerEnv.parse(host.base(), "r1", token, state.toString(), "2", null, null, null);
  }

  private ClientCredentials ensure(String token) throws Exception {
    return new Registration(http, 5_000).ensure(env(token), CAPS);
  }

  @Test
  void aFirstStartRegistersWithTheTokenAsBearerAndWritesTheClientOwnerOnly() throws Exception {
    ClientCredentials client = ensure("one-time-token");

    assertEquals("ci-runner-r1", client.clientId());
    assertEquals(1, host.registerBodies.size());
    assertEquals("Bearer one-time-token", host.registerAuthorizations.getFirst());
    JsonObject body = new JsonObject(host.registerBodies.getFirst());
    assertEquals(
        new JsonObject()
            .put("docker", true)
            .put("arch", "amd64")
            .put("os", "linux")
            .put("labels", new JsonObject().put("site", "home")),
        body.getJsonObject("capabilities"));

    Path file = state.resolve("client.json");
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    JsonObject stored = new JsonObject(Files.readString(file));
    assertEquals("client-secret", stored.getString("secret"));
    assertEquals(host.base() + "/token", stored.getString("tokenUrl"));
    assertFalse(Files.readString(file).contains("one-time-token"), "the token itself is never kept");
    assertFalse(Files.exists(state.resolve("client.json.tmp")));
  }

  @Test
  void aStoredClientIsUsedAndTheDoorIsNotCalledAgain() throws Exception {
    ensure("one-time-token");
    ClientCredentials again = ensure("one-time-token");
    ClientCredentials withoutToken = ensure(null);

    assertEquals(1, host.registerBodies.size(), "the token is dead after its first use");
    assertEquals("ci-runner-r1", again.clientId());
    assertEquals("ci-runner-r1", withoutToken.clientId());
  }

  @Test
  void aNewTokenIsARotationAndRegistersAgain() throws Exception {
    ensure("first-token");
    host.registerBody = host.clientJson().replace("ci-runner-r1", "ci-runner-r1-rotated");
    ClientCredentials rotated = ensure("second-token");

    assertEquals(2, host.registerBodies.size());
    assertEquals("Bearer second-token", host.registerAuthorizations.get(1));
    assertEquals("ci-runner-r1-rotated", rotated.clientId());
    assertEquals(
        "ci-runner-r1-rotated",
        new JsonObject(Files.readString(state.resolve("client.json"))).getString("clientId"));
  }

  @Test
  void anUnregisteredRunnerWithNoTokenIsMisconfiguredAndNamesTheVariable() {
    Registration.Failed failed = assertThrows(Registration.Failed.class, () -> ensure(null));
    assertEquals(ExitCode.MISCONFIGURED, failed.exitCode());
    assertTrue(failed.getMessage().startsWith("QITS_CI_RUNNER_REGISTRATION_TOKEN is not set"));
    assertEquals(0, host.registerBodies.size());
  }

  @Test
  void aRefusalIsExitFiveWithTheBodyQuoted() {
    host.registerStatus = 401;
    host.registerBody = "{\"error\":\"registration token already used\"}";
    Registration.Failed failed = assertThrows(Registration.Failed.class, () -> ensure("used"));
    assertEquals(ExitCode.REGISTRATION_REFUSED, failed.exitCode());
    assertTrue(failed.getMessage().contains("(401)"), failed::getMessage);
    assertTrue(
        failed.getMessage().contains("\"{\"error\":\"registration token already used\"}\""),
        failed::getMessage);
    assertFalse(Files.exists(state.resolve("client.json")));
  }

  @Test
  void aServerErrorIsWorthARestartNotARefusal() {
    host.registerStatus = 503;
    host.registerBody = "down for maintenance";
    assertEquals(
        ExitCode.REGISTRATION_UNREACHABLE,
        assertThrows(Registration.Failed.class, () -> ensure("t")).exitCode());
  }

  @Test
  void aStoredFileThatIsNotAClientIsStateUnusable() throws Exception {
    Files.writeString(state.resolve("client.json"), "{\"clientId\":\"x\"}");
    assertEquals(
        ExitCode.STATE_UNUSABLE,
        assertThrows(Registration.Failed.class, () -> ensure(null)).exitCode());
  }
}

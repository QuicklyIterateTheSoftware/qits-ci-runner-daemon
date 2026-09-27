package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The env contract. A missing or unparseable value is one sentence naming the VARIABLE — what the
 * operator edits in {@code /etc/qits-ci-runner.env} — and never a value, which could be the token.
 */
class RunnerEnvTest {

  private static RunnerEnv.Invalid invalid(
      String url, String id, String slots, String timeout) {
    return assertThrows(
        RunnerEnv.Invalid.class,
        () -> RunnerEnv.parse(url, id, "tok", null, slots, null, timeout, null));
  }

  @Test
  void noUrlIsNamedByItsVariable() {
    assertEquals("QITS_CI_RUNNER_URL is not set", invalid(null, "r1", null, null).getMessage());
    assertEquals("QITS_CI_RUNNER_URL is not set", invalid("  ", "r1", null, null).getMessage());
  }

  @Test
  void aUrlThatIsNotHttpIsRefusedBeforeAnythingDialsIt() {
    assertTrue(invalid("ci.example", "r1", null, null).getMessage().startsWith("QITS_CI_RUNNER_URL"));
  }

  @Test
  void noIdIsNamedByItsVariable() {
    assertEquals(
        "QITS_CI_RUNNER_ID is not set",
        invalid("https://ci.example", null, null, null).getMessage());
  }

  @Test
  void anIdOutsideTheLabelCharsetIsRefused() {
    // It becomes a path segment and a docker label value; neither may need escaping.
    assertTrue(
        invalid("https://ci.example", "r1/../x", null, null).getMessage().startsWith("QITS_CI_RUNNER_ID"));
  }

  @Test
  void aNonNumericSlotCountOrTimeoutNamesItsVariable() {
    assertTrue(
        invalid("https://ci.example", "r1", "two", null).getMessage().startsWith("QITS_CI_RUNNER_SLOTS"));
    assertTrue(
        invalid("https://ci.example", "r1", "0", null).getMessage().startsWith("QITS_CI_RUNNER_SLOTS"));
    assertTrue(
        invalid("https://ci.example", "r1", null, "soon")
            .getMessage()
            .startsWith("QITS_CI_RUNNER_DOCKER_TIMEOUT"));
  }

  @Test
  void theDefaultsAreTheDocumentedOnes() throws Exception {
    RunnerEnv env =
        RunnerEnv.parse("https://ci.example/", "r1", null, null, null, null, null, null);
    assertEquals("https://ci.example", env.url(), "a trailing slash is dropped once, here");
    assertEquals("", env.registrationToken());
    assertEquals(Path.of("/var/lib/qits-ci-runner"), env.stateDir());
    assertEquals(1, env.slots());
    assertEquals("docker", env.dockerBinary());
    assertEquals(120, env.dockerTimeoutSeconds());
    assertEquals("moby/buildkit:v0.33.0", env.buildkitImage());
  }

  @Test
  void aTimeoutMayCarryItsUnit() throws Exception {
    assertEquals(
        45,
        RunnerEnv.parse("http://dev-qits-ci:8080", "r1", null, null, "3", null, "45s", null)
            .dockerTimeoutSeconds());
  }
}

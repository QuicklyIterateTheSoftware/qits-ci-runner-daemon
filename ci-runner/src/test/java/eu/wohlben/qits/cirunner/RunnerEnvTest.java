package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
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
    assertEquals(BuildPlane.STATE_VOLUME, env.buildkitStateVolume());
  }

  @Test
  void theBuildkitStateVolumeDefaultsToBuildPlanesAndIsOverridable() throws Exception {
    RunnerEnv unset =
        RunnerEnv.parse(
            "https://ci.example", "r1", null, null, null, null, null, null, null, null, null, null,
            null);
    assertEquals("qits-buildkitd-state", unset.buildkitStateVolume());
    assertEquals(BuildPlane.STATE_VOLUME, unset.buildkitStateVolume());

    RunnerEnv blank =
        RunnerEnv.parse(
            "https://ci.example", "r1", null, null, null, null, null, null, null, null, null, null,
            "  ");
    assertEquals(BuildPlane.STATE_VOLUME, blank.buildkitStateVolume());

    RunnerEnv set =
        RunnerEnv.parse(
            "https://ci.example", "r1", null, null, null, null, null, null, null, null, null, null,
            " operators-own-buildkitd-state ");
    assertEquals("operators-own-buildkitd-state", set.buildkitStateVolume());
  }

  @Test
  void aTimeoutMayCarryItsUnit() throws Exception {
    assertEquals(
        45,
        RunnerEnv.parse("http://dev-qits-ci:8080", "r1", null, null, "3", null, "45s", null)
            .dockerTimeoutSeconds());
  }

  @Test
  void theBuilderRegistryListsDefaultEmptyAndParseCommaSeparated() throws Exception {
    RunnerEnv none =
        RunnerEnv.parse("https://ci.example", "r1", null, null, null, null, null, null);
    assertEquals(List.of(), none.buildkitHttpRegistries());
    assertEquals(List.of(), none.buildkitRegistryMirrors());

    RunnerEnv set =
        RunnerEnv.parse(
            "http://dev-qits-ci:8080", "r1", null, null, null, null, null, null,
            " dev-qits-artifacts:8080 , dev-qits-platform-mirror:8080,",
            "registry.dev.localhost:8080=dev-qits-artifacts:8080,docker.io=dev-qits-platform-mirror:8080/hub");
    assertEquals(
        List.of("dev-qits-artifacts:8080", "dev-qits-platform-mirror:8080"),
        set.buildkitHttpRegistries());
    assertEquals(
        List.of(
            "registry.dev.localhost:8080=dev-qits-artifacts:8080",
            "docker.io=dev-qits-platform-mirror:8080/hub"),
        set.buildkitRegistryMirrors());
  }

  @Test
  void aRegistryEntryThatWouldBreakOutOfItsTomlStringIsRefused() {
    // The values are rendered inside quoted TOML keys and strings; a quote or a newline there would
    // be configuration nobody wrote.
    assertTrue(
        assertThrows(
                RunnerEnv.Invalid.class,
                () ->
                    RunnerEnv.parse(
                        "https://ci.example", "r1", null, null, null, null, null, null,
                        "evil\"]\n[worker.oci]", null))
            .getMessage()
            .startsWith("QITS_CI_RUNNER_BUILDKIT_HTTP_REGISTRIES"));
    assertTrue(
        assertThrows(
                RunnerEnv.Invalid.class,
                () ->
                    RunnerEnv.parse(
                        "https://ci.example", "r1", null, null, null, null, null, null, null,
                        "docker.io"))
            .getMessage()
            .startsWith("QITS_CI_RUNNER_BUILDKIT_REGISTRY_MIRRORS"));
  }

  @Test
  void theRolloverTimeoutDefaultsToThreeMinutesAndTakesSeconds() throws Exception {
    assertEquals(
        180,
        RunnerEnv.parse("https://ci.example", "r1", null, null, null, null, null, null)
            .rolloverTimeoutSeconds());
    assertEquals(
        45,
        RunnerEnv.parse(
                "https://ci.example", "r1", null, null, null, null, null, null, null, null, "45s")
            .rolloverTimeoutSeconds());
    assertTrue(
        assertThrows(
                RunnerEnv.Invalid.class,
                () ->
                    RunnerEnv.parse(
                        "https://ci.example", "r1", null, null, null, null, null, null, null, null,
                        "soon"))
            .getMessage()
            .startsWith("QITS_CI_RUNNER_ROLLOVER_TIMEOUT"));
  }

  @Test
  void theTelemetryUrlIsTheCisEdgeSiblingUnlessSetAndOffWhenSetEmpty() throws Exception {
    assertEquals(
        "https://observability.qits.example.eu/observability/api/otel",
        RunnerEnv.telemetryUrl(null, "https://ci.qits.example.eu"));
    assertEquals(
        "https://observability.qits.example.eu:8443/observability/api/otel",
        RunnerEnv.telemetryUrl(null, "https://CI.qits.example.eu:8443/ci"));
    assertNull(
        RunnerEnv.telemetryUrl(null, "http://dev-qits-ci:8080"),
        "an address with no ci. label has no sibling to derive");
    assertNull(RunnerEnv.telemetryUrl("", "https://ci.qits.example.eu"), "set and empty is off");
    assertNull(RunnerEnv.telemetryUrl("  ", "https://ci.qits.example.eu"));
    assertEquals(
        "http://collector:4318",
        RunnerEnv.telemetryUrl("http://collector:4318/", "https://ci.qits.example.eu"));
    assertTrue(
        assertThrows(
                RunnerEnv.Invalid.class,
                () -> RunnerEnv.telemetryUrl("collector:4318", "https://ci.qits.example.eu"))
            .getMessage()
            .startsWith("QITS_CI_RUNNER_TELEMETRY_URL"));
  }

  @Test
  void selfUpdateDefaultsOnAndParsesTrueOrFalse() throws Exception {
    assertTrue(selfUpdate(null));
    assertTrue(selfUpdate(" "));
    assertTrue(selfUpdate("true"));
    assertTrue(selfUpdate("1"));
    assertFalse(selfUpdate("false"));
    assertFalse(selfUpdate("FALSE"));
    assertFalse(selfUpdate("0"));
    RunnerEnv.Invalid invalid =
        assertThrows(
            RunnerEnv.Invalid.class, () -> selfUpdate("no"));
    assertEquals("QITS_CI_RUNNER_SELF_UPDATE is not true or false: 'no'", invalid.getMessage());
  }

  private static boolean selfUpdate(String value) throws RunnerEnv.Invalid {
    return RunnerEnv.parse(
            "https://ci.example", "r1", null, null, null, null, null, null, null, null, null, value)
        .selfUpdate();
  }
}

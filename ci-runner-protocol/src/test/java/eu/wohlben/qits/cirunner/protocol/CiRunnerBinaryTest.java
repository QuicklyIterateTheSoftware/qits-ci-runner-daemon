package eu.wohlben.qits.cirunner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pin resolves — the guard on this module's resource-filtering block, which is the one thing
 * about {@link CiRunnerBinary} that can silently break. It asserts the shape and not a value: the
 * version is whatever stamped this tree, a CalVer after a release and a {@code -SNAPSHOT} before
 * the first one.
 */
class CiRunnerBinaryTest {

  @Test
  void theVersionIsFilteredInAndIsACalVerOrASnapshot() {
    String version = CiRunnerBinary.VERSION;
    assertFalse(version.isBlank(), "the version is blank");
    assertFalse(
        version.startsWith("$"),
        () -> "an unfiltered ${project.version} reached the jar: " + version);
    assertTrue(
        version.matches("[0-9][0-9.]*[0-9](-SNAPSHOT)?"),
        () -> "not a CalVer — is resource filtering still on? got: " + version);
  }

  @Test
  void theRunnerNameIsTheOneThePipelinePublishesUnder() {
    // A literal, because it is a cross-repository contract: `.config/qits/release.yml` declares it
    // in `artifacts:`, and qits-ci's install script downloads from that path segment.
    assertEquals("qits-ci-runner", CiRunnerBinary.RUNNER_NAME);
  }
}

package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The decisions {@link Main} makes before any plain class is built. */
class MainTest {

  @Test
  void onlyAnExplicitOneAsksForTheVersion() {
    assertTrue(Main.printsVersion("1"));
    assertTrue(Main.printsVersion("true"));
    assertFalse(Main.printsVersion(null));
    assertFalse(Main.printsVersion(""));
    assertFalse(Main.printsVersion("0"));
  }

  @Test
  void inAContainerTheBuilderIsGivenNoHostCaBundleToProbe() {
    // The probe would find the image's own bundle and the bind would be resolved on the host.
    assertEquals(List.of(), Main.caBundleCandidates(Optional.of("0123456789ab")));
    assertEquals(BuildPlane.HOST_CA_BUNDLE_CANDIDATES, Main.caBundleCandidates(Optional.empty()));
  }

  @Test
  void everyShippedLineSaysWhichRunnerOnWhichContainerAndArch() {
    eu.wohlben.qits.cirunner.protocol.Capabilities caps =
        new eu.wohlben.qits.cirunner.protocol.Capabilities(true, "arm64", "linux", java.util.Map.of());
    java.util.Map<String, String> resource =
        Main.telemetryResource("r1", Optional.of("0123456789ab"), caps);
    assertEquals("qits-ci-runner", resource.get("service.name"));
    assertEquals(
        eu.wohlben.qits.cirunner.protocol.CiRunnerBinary.VERSION, resource.get("service.version"));
    assertEquals("0123456789ab", resource.get("service.instance.id"));
    assertEquals("r1", resource.get("qits.ci.runner.id"));
    assertEquals("arm64", resource.get("host.arch"));
    assertEquals("linux", resource.get("os.type"));
    assertFalse(
        resource.containsKey("qits.workspace.id"),
        "the workspace pair would bucket the runner away from its service.name");
    assertEquals("r1", Main.telemetryResource("r1", Optional.empty(), caps).get("service.instance.id"));
  }

  @Test
  void aRunnerWithSelfUpdateOffSaysSoInItsCapabilityLabels(@org.junit.jupiter.api.io.TempDir java.nio.file.Path proc) {
    assertEquals(
        java.util.Map.of("qits.ci.runner.self-update", "false"),
        Main.capabilities(proc, false).labels());
    assertEquals(java.util.Map.of(), Main.capabilities(proc, true).labels(), "the default adds none");
  }
}

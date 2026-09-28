package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The two decisions {@link Main} makes before any plain class is built. */
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
}

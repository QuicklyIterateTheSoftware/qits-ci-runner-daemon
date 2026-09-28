package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class BuildPlaneTest {

  @TempDir Path dir;

  private static List<List<String>> bare(List<List<String>> calls) {
    return calls;
  }

  @Test
  void aHostWithNoBuilderGetsTheNetworkTheVolumeAndOnePrivilegedBuilder() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("network-inspect", 1, "", "network qits-ci-runner not found")
            .answer("inspect", 1, "", "No such object")
            .answer("image-inspect", 1, "", "No such image");
    BuildPlane plane = new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0");

    assertEquals(Optional.empty(), plane.ensure(null));
    List<List<String>> calls = bare(fake.calls());
    assertEquals(List.of("network", "inspect", "qits-ci-runner"), calls.get(0));
    assertEquals(
        List.of(
            "network", "create", "--driver", "bridge", "--label", "qits.ci.runner.buildkitd=network",
            "qits-ci-runner"),
        calls.get(1));
    assertEquals(List.of("volume", "create", "qits-buildkitd-state"), calls.get(2));
    assertEquals(List.of("pull", "moby/buildkit:v0.33.0"), calls.get(5));
    List<String> run = calls.get(6);
    List<String> expected = plane.runArgv(plane.stamp());
    assertEquals(expected.subList(1, expected.size()), run);
    assertTrue(run.contains("--privileged"));
    assertEquals("qits-ci-runner-buildkitd", run.get(run.indexOf("--name") + 1));
    assertEquals("qits-buildkitd-state:/var/lib/buildkit", run.get(run.indexOf("-v") + 1));
    assertTrue(run.get(run.indexOf("-e") + 1).contains("networkMode = \"host\""));
  }

  @Test
  void aRunningBuilderWithTheConfiguredStampIsAdoptedUntouched() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    BuildPlane plane = new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0");
    fake.answer("inspect", 0, plane.stamp() + "|running\n", "");

    assertEquals(Optional.empty(), plane.ensure(null));
    assertEquals(0, fake.calls("run").size());
    assertEquals(0, fake.calls("rm").size());
    assertEquals(0, fake.calls("start").size());
  }

  @Test
  void aStoppedBuilderOfOursIsStartedNotReplaced() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    BuildPlane plane = new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0");
    fake.answer("inspect", 0, plane.stamp() + "|exited\n", "");

    assertEquals(Optional.empty(), plane.ensure(null));
    assertEquals(List.of(List.of("start", "qits-ci-runner-buildkitd")), bare(fake.calls("start")));
    assertEquals(0, fake.calls("run").size());
  }

  @Test
  void aBumpedPinReplacesOurBuilderAndKeepsTheVolume() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("inspect", 0, "0000000000000000|running\n", "");
    BuildPlane plane = new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.34.0");

    assertEquals(Optional.empty(), plane.ensure(null));
    assertEquals(List.of(List.of("rm", "-f", "qits-ci-runner-buildkitd")), bare(fake.calls("rm")));
    assertEquals(1, fake.calls("run").size());
    assertEquals(0, fake.calls().stream().filter(c -> c.contains("rm") && c.contains("volume")).count());
  }

  @Test
  void aContainerUnderTheNameThatIsNotOursIsRefusedAndLeftAlone() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("inspect", 0, "<no value>|running\n", "");
    BuildPlane plane = new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0");

    Optional<String> failure = plane.ensure(null);
    assertTrue(failure.isPresent());
    assertTrue(failure.get().contains("not this runner's builder"), failure::get);
    assertEquals(0, fake.calls("rm").size());
    assertEquals(0, fake.calls("run").size());
  }

  @Test
  void theBuilderJoinsAStepsNamedNetworkAndAnAlreadyJoinedAnswerIsFine() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    BuildPlane plane = new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0");
    fake.answer("inspect", 0, plane.stamp() + "|running\n", "")
        .answer("network-connect", 1, "", "endpoint with name qits-ci-runner-buildkitd already exists in network qits-net");

    assertEquals(Optional.empty(), plane.ensure("qits-net"));
    assertTrue(
        bare(fake.calls("network")).contains(
            List.of("network", "connect", "qits-net", "qits-ci-runner-buildkitd")));
  }

  /** What a runner on the platform host's qits-net sets — qits-containers' values, abridged. */
  private static final List<String> HTTP = List.of("dev-qits-artifacts:8080", "dev-qits-platform-mirror:8080");

  private static final List<String> MIRRORS =
      List.of(
          "registry.dev.localhost:8080=dev-qits-artifacts:8080",
          "docker.io=dev-qits-platform-mirror:8080/hub",
          "dev-qits-artifacts:8080=dev-qits-artifacts:8080");

  @Test
  void anUnconfiguredBuilderRendersOnlyTheNamespaceAndDnsSettings() {
    assertEquals(
        """
        [worker.oci]
          networkMode = "host"
          gc = true
        [dns]
          nameservers = ["127.0.0.11"]
        """,
        BuildPlane.renderToml(List.of(), List.of()));
  }

  @Test
  void mirrorsAndPlainHttpRegistriesRenderOneTablePerHost() {
    assertEquals(
        """
        [worker.oci]
          networkMode = "host"
          gc = true
        [dns]
          nameservers = ["127.0.0.11"]
        [registry."registry.dev.localhost:8080"]
          mirrors = ["dev-qits-artifacts:8080"]
        [registry."docker.io"]
          mirrors = ["dev-qits-platform-mirror:8080/hub"]
        [registry."dev-qits-artifacts:8080"]
          mirrors = ["dev-qits-artifacts:8080"]
          http = true
        [registry."dev-qits-platform-mirror:8080"]
          http = true
        """,
        BuildPlane.renderToml(HTTP, MIRRORS));
  }

  @Test
  void theRenderedTomlIsWhatTheBuilderIsStartedWith() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("inspect", 1, "", "No such object");
    BuildPlane plane = new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0", HTTP, MIRRORS);

    assertEquals(Optional.empty(), plane.ensure(null));
    List<String> run = fake.calls("run").getFirst();
    assertEquals(
        "BUILDKITD_TOML=" + BuildPlane.renderToml(HTTP, MIRRORS), run.get(run.indexOf("-e") + 1));
    assertTrue(run.contains("qits.ci.runner.buildkitd=" + plane.stamp()));
  }

  @Test
  void aChangedRegistrySettingChangesTheStampAndRecreatesTheBuilder() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    BuildPlane before = new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0");
    BuildPlane after =
        new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0", HTTP, List.of());
    assertTrue(!before.stamp().equals(after.stamp()), "the toml is stamp material");
    // The running builder was started under the old configuration.
    fake.answer("inspect", 0, before.stamp() + "|running\n", "");

    assertEquals(Optional.empty(), after.ensure(null));
    assertEquals(List.of(List.of("rm", "-f", "qits-ci-runner-buildkitd")), fake.calls("rm"));
    List<String> run = fake.calls("run").getFirst();
    assertTrue(run.contains("qits.ci.runner.buildkitd=" + after.stamp()));
    assertTrue(run.get(run.indexOf("-e") + 1).contains("[registry.\"dev-qits-artifacts:8080\"]\n  http = true"));
  }

  @Test
  void aFoundHostCaBundleIsMountedReadOnlyAtTheImagesOwnPath() throws Exception {
    Path bundle = dir.resolve("ca-certificates.crt");
    Files.writeString(bundle, "-----BEGIN CERTIFICATE-----\n");
    FakeDocker fake = new FakeDocker(dir).answer("inspect", 1, "", "No such object");
    BuildPlane plane =
        new BuildPlane(
            fake.docker(10),
            fake.binary,
            "moby/buildkit:v0.33.0",
            List.of(),
            List.of(),
            List.of(bundle.toString(), "/does/not/exist"));

    assertEquals(Optional.empty(), plane.ensure(null));
    List<String> run = fake.calls("run").getFirst();
    assertTrue(
        run.contains(bundle + ":/etc/ssl/certs/ca-certificates.crt:ro"),
        () -> "no CA mount in " + run);
  }

  @Test
  void noHostCaBundleFoundMountsNothing() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("inspect", 1, "", "No such object");
    BuildPlane plane =
        new BuildPlane(
            fake.docker(10),
            fake.binary,
            "moby/buildkit:v0.33.0",
            List.of(),
            List.of(),
            List.of("/does/not/exist", "/also/not/there"));

    assertEquals(Optional.empty(), plane.ensure(null));
    List<String> run = fake.calls("run").getFirst();
    assertTrue(
        run.stream().noneMatch(a -> a.endsWith(":/etc/ssl/certs/ca-certificates.crt:ro")),
        () -> "unexpected CA mount in " + run);
  }

  @Test
  void theCaBundlePathIsStampMaterialAndItsAbsenceReplacesTheBuilder() throws Exception {
    Path bundle = dir.resolve("ca-certificates.crt");
    Files.writeString(bundle, "-----BEGIN CERTIFICATE-----\n");
    FakeDocker fake = new FakeDocker(dir);
    BuildPlane withBundle =
        new BuildPlane(
            fake.docker(10), fake.binary, "moby/buildkit:v0.33.0", List.of(), List.of(),
            List.of(bundle.toString()));
    BuildPlane withoutBundle =
        new BuildPlane(
            fake.docker(10), fake.binary, "moby/buildkit:v0.33.0", List.of(), List.of(),
            List.of("/does/not/exist"));
    assertTrue(
        !withBundle.stamp().equals(withoutBundle.stamp()), "the CA bundle path is stamp material");
    // The running builder was started with the bundle mounted.
    fake.answer("inspect", 0, withBundle.stamp() + "|running\n", "");

    assertEquals(Optional.empty(), withoutBundle.ensure(null));
    assertEquals(List.of(List.of("rm", "-f", "qits-ci-runner-buildkitd")), fake.calls("rm"));
  }

  /**
   * What qits-ci's Ack carries for a remote runner: the committed spelling to the public name. A
   * {@code LinkedHashMap}, not {@code Map.of} — its iteration order is salted per JVM and would make
   * the rendering-order assertions below flake about one run in two.
   */
  private static final Map<String, String> ACK_MIRRORS = new java.util.LinkedHashMap<>();

  static {
    ACK_MIRRORS.put("mirror.dev.localhost:8080", "mirror.qits.wohlben.eu");
    ACK_MIRRORS.put("registry.dev.localhost:8080", "registry.qits.wohlben.eu");
  }

  @Test
  void ackMirrorsAreAddedAsTheirOwnTablesWithNoHttpFlag() {
    BuildPlane plane = new BuildPlane(null, "docker", "moby/buildkit:v0.33.0");
    BuildPlane rewritten = plane.withRegistryMirrors(ACK_MIRRORS);

    assertEquals(
        """
        [worker.oci]
          networkMode = "host"
          gc = true
        [dns]
          nameservers = ["127.0.0.11"]
        [registry."mirror.dev.localhost:8080"]
          mirrors = ["mirror.qits.wohlben.eu"]
        [registry."registry.dev.localhost:8080"]
          mirrors = ["registry.qits.wohlben.eu"]
        """,
        rewritten.toml());
  }

  @Test
  void ackMirrorsOverrideAnEnvConfiguredMirrorForTheSameHostAndLeaveOthersAlone() {
    // registry.dev.localhost:8080 is env-configured to an internal mirror; the ack rewrites it to
    // the public one instead. docker.io, untouched by the ack, keeps its env-configured mirror.
    BuildPlane plane = new BuildPlane(null, "docker", "moby/buildkit:v0.33.0", List.of(), MIRRORS);

    BuildPlane rewritten = plane.withRegistryMirrors(ACK_MIRRORS);

    assertEquals(
        """
        [worker.oci]
          networkMode = "host"
          gc = true
        [dns]
          nameservers = ["127.0.0.11"]
        [registry."docker.io"]
          mirrors = ["dev-qits-platform-mirror:8080/hub"]
        [registry."dev-qits-artifacts:8080"]
          mirrors = ["dev-qits-artifacts:8080"]
        [registry."mirror.dev.localhost:8080"]
          mirrors = ["mirror.qits.wohlben.eu"]
        [registry."registry.dev.localhost:8080"]
          mirrors = ["registry.qits.wohlben.eu"]
        """,
        rewritten.toml());
  }

  @Test
  void aRewriteByAckLeavesTheImageHttpRegistriesAndCaBundleCandidatesUnchanged() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("inspect", 1, "", "No such object");
    BuildPlane plane =
        new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0", HTTP, List.of());
    BuildPlane rewritten = plane.withRegistryMirrors(ACK_MIRRORS);

    assertEquals(Optional.empty(), rewritten.ensure(null));
    List<String> run = fake.calls("run").getFirst();
    assertTrue(run.contains(RunnerArgv.requireImage("moby/buildkit:v0.33.0")));
    // The env http registries still render http = true; the ack's public targets never do.
    assertTrue(run.get(run.indexOf("-e") + 1).contains("[registry.\"dev-qits-artifacts:8080\"]\n  http = true"));
    assertFalse(
        run.get(run.indexOf("-e") + 1).contains("mirror.qits.wohlben.eu\"]\n  http = true"),
        "an ack mirror target is HTTPS with a public certificate, never http = true");
  }

  @Test
  void ackMirrorsChangeTheStampSoTheNextEnsureRecreatesTheBuilder() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    BuildPlane plane = new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0");
    BuildPlane rewritten = plane.withRegistryMirrors(ACK_MIRRORS);
    assertTrue(!plane.stamp().equals(rewritten.stamp()), "a changed mirror map is stamp material");
    // The running builder was started under the env-only configuration.
    fake.answer("inspect", 0, plane.stamp() + "|running\n", "");

    assertEquals(Optional.empty(), rewritten.ensure(null));
    assertEquals(List.of(List.of("rm", "-f", "qits-ci-runner-buildkitd")), fake.calls("rm"));
    assertEquals(1, fake.calls("run").size());
  }

  @Test
  void anUnchangedConfigurationIsAdoptedRatherThanRecreated() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    BuildPlane plane =
        new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0", HTTP, MIRRORS);
    BuildPlane sameAgain =
        new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0", HTTP, MIRRORS);
    assertEquals(plane.stamp(), sameAgain.stamp(), "the rendering is deterministic");
    fake.answer("inspect", 0, plane.stamp() + "|running\n", "");

    assertEquals(Optional.empty(), sameAgain.ensure(null));
    assertEquals(0, fake.calls("rm").size());
    assertEquals(0, fake.calls("run").size());
  }
}

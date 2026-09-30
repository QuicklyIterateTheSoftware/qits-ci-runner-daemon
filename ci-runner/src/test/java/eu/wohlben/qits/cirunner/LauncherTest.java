package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cirunner.protocol.Launch;
import eu.wohlben.qits.cirunner.protocol.LaunchFailed;
import eu.wohlben.qits.cirunner.protocol.Launched;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class LauncherTest {

  @TempDir Path dir;

  private Launcher launcher(FakeDocker fake) {
    return new Launcher(
        fake.docker(10), fake.binary, "r1", new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0"));
  }

  private static WorkloadSpec building(String network, Map<String, String> env, boolean socket) {
    return new WorkloadSpec(
        "alpine:3", null, null, env, null, network, null, null, socket, true, true, null, null,
        null, null, null, "qits-ci-run-1-x-0", !socket);
  }

  private static String envValue(List<String> run, String key) {
    for (int i = 0; i < run.size() - 1; i++) {
      if (run.get(i).equals("-e") && run.get(i + 1).startsWith(key + "=")) {
        return run.get(i + 1).substring(key.length() + 1);
      }
    }
    return null;
  }

  @Test
  void aPresentImageIsNotPulledAndTheContainerIdIsAnswered() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("run", 0, "0123abcdef\n", "");
    var answer =
        launcher(fake).launch(new Launch("run-1", 0, WorkloadSpec.of("alpine:3", "qits-ci-run-1-x-0")));
    assertEquals(new Launched("run-1", 0, "0123abcdef"), answer);
    assertEquals(0, fake.calls("pull").size());
    assertEquals(1, fake.calls("image").size());
  }

  @Test
  void anAbsentImageIsPulledBeforeTheRun() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("image-inspect", 1, "", "No such image")
            .answer("run", 0, "cid\n", "");
    assertInstanceOf(
        Launched.class,
        launcher(fake).launch(new Launch("run-1", 0, WorkloadSpec.of("alpine:3", "c0"))));
    List<List<String>> calls = fake.calls();
    assertEquals(List.of("pull", "alpine:3"), calls.get(1));
    assertEquals("run", calls.get(3).get(0));
  }

  @Test
  void aStaleContainerHoldingTheNameIsRemovedBeforeTheRun() throws Exception {
    // docker echoes the name it removed.
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("rm", 0, "qits-ci-run-1-x-0\n", "")
            .answer("run", 0, "cid\n", "");
    assertEquals(
        new Launched("run-1", 0, "cid"),
        launcher(fake).launch(new Launch("run-1", 0, WorkloadSpec.of("alpine:3", "qits-ci-run-1-x-0"))));
    List<List<String>> calls = fake.calls();
    int rm = calls.indexOf(List.of("rm", "-f", "qits-ci-run-1-x-0"));
    assertTrue(rm >= 0, () -> "the name is cleared: " + calls);
    assertEquals(1, fake.calls("rm").size());
    assertEquals("run", calls.get(rm + 1).get(0), "and the run is the very next call");
    assertEquals(calls.size() - 1, rm + 1);
  }

  @Test
  void noSuchContainerIsTheOrdinaryCaseAndTheRunGoesAhead() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("rm", 1, "", "Error response from daemon: No such container: c0")
            .answer("run", 0, "cid\n", "");
    assertEquals(
        new Launched("run-1", 0, "cid"),
        launcher(fake).launch(new Launch("run-1", 0, WorkloadSpec.of("alpine:3", "c0"))));
    assertEquals(List.of(List.of("rm", "-f", "c0")), fake.calls("rm"));
  }

  @Test
  void aRemovalDockerRefusesLeavesTheVerdictToTheRun() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("rm", 1, "", "cannot remove container: permission denied")
            .answer("run", 125, "", "Conflict. The container name \"/c0\" is already in use");
    LaunchFailed failed =
        assertInstanceOf(
            LaunchFailed.class,
            launcher(fake).launch(new Launch("run-1", 0, WorkloadSpec.of("alpine:3", "c0"))));
    assertTrue(failed.detail().contains("already in use"), failed::detail);
  }

  @Test
  void aFailedPullRemovesNothing() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir).answer("image-inspect", 1, "", "").answer("pull", 1, "", "denied");
    launcher(fake).launch(new Launch("run-1", 0, WorkloadSpec.of("nope:1", "c0")));
    assertEquals(0, fake.calls("rm").size());
  }

  @Test
  void aPullThatFailsIsALaunchFailedCarryingDockersWordsAndNoRun() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("image-inspect", 1, "", "")
            .answer("pull", 1, "", "pull access denied for nope, repository does not exist");
    LaunchFailed failed =
        assertInstanceOf(
            LaunchFailed.class,
            launcher(fake).launch(new Launch("run-1", 2, WorkloadSpec.of("nope:1", "c2"))));
    assertEquals(2, failed.stepIndex());
    assertTrue(failed.detail().contains("pull access denied"), failed::detail);
    assertEquals(0, fake.calls("run").size());
  }

  @Test
  void aRunThatFailsIsALaunchFailedWithStderrBounded() throws Exception {
    String longError = "x".repeat(5000) + " the last words";
    FakeDocker fake = new FakeDocker(dir).answer("run", 125, "", longError);
    LaunchFailed failed =
        assertInstanceOf(
            LaunchFailed.class,
            launcher(fake).launch(new Launch("run-1", 0, WorkloadSpec.of("alpine:3", "c0"))));
    assertTrue(failed.detail().endsWith("the last words"), "the tail is where docker's verdict is");
    assertTrue(failed.detail().length() <= Launcher.MAX_DETAIL + 1);
  }

  @Test
  void aRefusedSpecNeverReachesDocker() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    LaunchFailed failed =
        assertInstanceOf(
            LaunchFailed.class,
            launcher(fake).launch(new Launch("run-1", 0, WorkloadSpec.of("--privileged", "c0"))));
    assertTrue(failed.detail().startsWith("refused by the runner"), failed::detail);
    assertEquals(List.of(), fake.calls());
  }

  @Test
  void aBuildingStepGetsTheRunnersBuilderAddressAndNetwork() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("inspect", 1, "", "No such object")
            .answer("run", 0, "cid\n", "");
    assertInstanceOf(
        Launched.class,
        launcher(fake).launch(new Launch("run-1", 0, building(null, Map.of("CI", "true"), false))));
    List<List<String>> all = fake.calls();
    List<String> step =
        all.stream().filter(c -> c.get(0).equals("run") && c.contains("qits-ci-run-1-x-0")).findFirst().orElseThrow();
    assertEquals(BuildPlane.ADDRESS, envValue(step, "BUILDKIT_HOST"));
    assertEquals("qits-ci-runner", step.get(step.indexOf("--network") + 1));
    assertTrue(
        all.stream().anyMatch(c -> c.get(0).equals("run") && c.contains("--privileged")),
        "the builder came up first");
  }

  @Test
  void aBuildingStepWithNoNetworkNamedJoinsOnlyTheRunnerBridge() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("inspect", 1, "", "No such object")
            .answer("run", 0, "cid\n", "");
    launcher(fake).launch(new Launch("run-1", 0, building(null, Map.of(), false)));
    List<String> step =
        fake.calls().stream()
            .filter(c -> c.get(0).equals("run") && c.contains("qits-ci-run-1-x-0"))
            .findFirst()
            .orElseThrow();
    long networkFlags = step.stream().filter(a -> a.equals("--network")).count();
    assertEquals(1, networkFlags, () -> "expected exactly one --network flag in " + step);
    assertEquals("qits-ci-runner", step.get(step.indexOf("--network") + 1));
  }

  @Test
  void anEmptyBuildkitHostIsTheSwitchedOffValueAndIsNeverOverwritten() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir).answer("inspect", 1, "", "").answer("run", 0, "cid\n", "");
    assertInstanceOf(
        Launched.class,
        launcher(fake)
            .launch(new Launch("run-1", 0, building(null, Map.of("BUILDKIT_HOST", ""), false))));
    List<String> step =
        fake.calls().stream()
            .filter(c -> c.get(0).equals("run") && c.contains("qits-ci-run-1-x-0"))
            .findFirst()
            .orElseThrow();
    assertEquals("", envValue(step, "BUILDKIT_HOST"));
  }

  @Test
  void aPlainStepGetsNoBuilderAtAll() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("run", 0, "cid\n", "");
    launcher(fake).launch(new Launch("run-1", 0, WorkloadSpec.of("alpine:3", "c0")));
    assertFalse(fake.calls().stream().anyMatch(c -> c.contains("--privileged")));
    assertFalse(fake.calls().stream().anyMatch(c -> c.contains("BUILDKIT_HOST=" + BuildPlane.ADDRESS)));
    assertFalse(fake.calls().stream().anyMatch(c -> c.contains("qits-ci-runner")));
  }

  @Test
  void aSpecNamingANetworkKeepsItAndAddsTheRunnersForABuild() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir).answer("inspect", 1, "", "").answer("run", 0, "cid\n", "");
    launcher(fake).launch(new Launch("run-1", 0, building("qits-net", Map.of(), true)));
    List<String> step =
        fake.calls().stream()
            .filter(c -> c.get(0).equals("run") && c.contains("qits-ci-run-1-x-0"))
            .findFirst()
            .orElseThrow();
    assertEquals("qits-net", step.get(step.indexOf("--network") + 1));
    assertEquals("qits-ci-runner", step.get(step.lastIndexOf("--network") + 1));
    // …and the builder joined the step's plane, so its pulls resolve that plane's names.
    assertTrue(
        fake.calls().stream()
            .anyMatch(c -> c.equals(
                List.of("network", "connect", "qits-net", BuildPlane.CONTAINER))));
  }

  @Test
  void aBuildkitHostTheSpecAlreadyCarriesIsKept() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir).answer("inspect", 1, "", "").answer("run", 0, "cid\n", "");
    assertInstanceOf(
        Launched.class,
        launcher(fake)
            .launch(
                new Launch(
                    "run-1", 0, building(null, Map.of("BUILDKIT_HOST", "tcp://elsewhere:1234"), false))));
    List<String> step =
        fake.calls().stream()
            .filter(c -> c.get(0).equals("run") && c.contains("qits-ci-run-1-x-0"))
            .findFirst()
            .orElseThrow();
    assertEquals("tcp://elsewhere:1234", envValue(step, "BUILDKIT_HOST"));
  }

  // --- qits-ci's registry mirrors from Ack (qits-478) ---------------------------------------------

  @Test
  void anAckCarryingMirrorsRewritesTheBuilderTheNextBuildUses() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir).answer("inspect", 1, "", "No such object").answer("run", 0, "cid\n", "");
    Launcher launcher =
        new Launcher(fake.docker(10), fake.binary, "r1", new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0"));

    launcher.onAck(
        Map.of(
            "mirror.dev.localhost:8080", "mirror.qits.wohlben.eu",
            "registry.dev.localhost:8080", "registry.qits.wohlben.eu"));
    launcher.launch(new Launch("run-1", 0, building(null, Map.of(), false)));

    List<String> builder =
        fake.calls().stream().filter(c -> c.get(0).equals("run") && c.contains("--privileged")).findFirst().orElseThrow();
    String toml = builder.get(builder.indexOf("-e") + 1);
    assertTrue(toml.contains("[registry.\"mirror.dev.localhost:8080\"]\n  mirrors = [\"mirror.qits.wohlben.eu\"]"), toml);
    assertTrue(toml.contains("[registry.\"registry.dev.localhost:8080\"]\n  mirrors = [\"registry.qits.wohlben.eu\"]"), toml);
    assertFalse(toml.contains("http = true"), "an ack mirror target is https with a public certificate");
  }

  @Test
  void aNullOrEmptyAckLeavesTheBuilderConfigurationAlone() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir).answer("inspect", 1, "", "No such object").answer("run", 0, "cid\n", "");
    BuildPlane envPlane = new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0");
    Launcher launcher = new Launcher(fake.docker(10), fake.binary, "r1", envPlane);

    launcher.onAck(null);
    launcher.onAck(Map.of());
    launcher.launch(new Launch("run-1", 0, building(null, Map.of(), false)));

    List<String> builder =
        fake.calls().stream().filter(c -> c.get(0).equals("run") && c.contains("--privileged")).findFirst().orElseThrow();
    assertTrue(builder.contains("qits.ci.runner.buildkitd=" + envPlane.stamp()));
  }

  @Test
  void aSecondAckWithTheSameMirrorsDoesNotRecreateAnAlreadyRunningBuilder() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir).answer("inspect", 1, "", "No such object").answer("run", 0, "cid\n", "");
    Launcher launcher =
        new Launcher(fake.docker(10), fake.binary, "r1", new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0"));
    Map<String, String> mirrors = Map.of("mirror.dev.localhost:8080", "mirror.qits.wohlben.eu");
    launcher.onAck(mirrors);
    launcher.launch(new Launch("run-1", 0, building(null, Map.of(), false)));
    String stamp =
        fake.calls().stream()
            .filter(c -> c.get(0).equals("run") && c.contains("--privileged"))
            .findFirst()
            .orElseThrow()
            .stream()
            .filter(a -> a.startsWith("qits.ci.runner.buildkitd="))
            .findFirst()
            .orElseThrow();
    fake.answer("inspect", 0, stamp.substring(stamp.indexOf('=') + 1) + "|running\n", "");

    // Same map again — a slots-only Ack, say — must not force the builder to be replaced.
    launcher.onAck(mirrors);
    launcher.launch(new Launch("run-2", 0, building(null, Map.of(), false)));

    // The only removals are each launch clearing its own step name — never the builder.
    List<String> clearsTheStepName = List.of("rm", "-f", "qits-ci-run-1-x-0");
    assertEquals(List.of(clearsTheStepName, clearsTheStepName), fake.calls("rm"));
  }

  // --- the launch's own registry login (qits-478) ------------------------------------------------

  private static final String IMAGE =
      "registry.qits.example.org/qits/build-images/node-docker-base@sha256:"
          + "25cf82f5aa0f5a3a1c8b0f8e0d7f6e5d4c3b2a1908f7e6d5c4b3a29181706f5e";

  private static final String AUTH = "dG9rZW46cWl0c190b2tfcnVuLTE=";

  private static final String DOCUMENT =
      "{\"auths\":{\"registry.qits.example.org\":{\"auth\":\"" + AUTH + "\"}}}";

  private static WorkloadSpec plainWithEnv(String image, Map<String, String> env) {
    return new WorkloadSpec(
        image, null, null, env, null, null, null, null, false, true, true, null, null, null, null,
        null, "qits-ci-run-1-x-0", false);
  }

  @Test
  void aSpecCarryingARegistryLoginPullsUnderItAndTheDirectoryIsGoneAfterwards() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("image-inspect", 1, "", "No such image")
            .answer("run", 0, "cid\n", "");
    assertInstanceOf(
        Launched.class,
        launcher(fake)
            .launch(
                new Launch(
                    "run-1", 0, plainWithEnv(IMAGE, Map.of("QITS_CI_REGISTRY_AUTH_CONFIG", DOCUMENT)))));

    List<List<String>> calls = fake.calls();
    List<String> inspect = calls.get(0);
    List<String> pull = calls.get(1);
    assertEquals("--config", inspect.get(0));
    assertEquals(List.of("image", "inspect", "--format", "{{.Id}}", IMAGE), inspect.subList(2, inspect.size()));
    assertEquals("--config", pull.get(0));
    assertEquals(List.of("pull", IMAGE), pull.subList(2, pull.size()));
    Path configDir = Path.of(pull.get(1));
    assertEquals(inspect.get(1), pull.get(1), "one login for the one launch");
    // What docker found there, read by the fake at the moment it ran: the document, and nobody else's.
    assertEquals(List.of(List.of("700 600", DOCUMENT), List.of("700 600", DOCUMENT)), fake.configs());
    assertFalse(Files.exists(configDir), "the login outlives its pull: " + configDir);
    // The run is not given it — the image is local by then — and the step keeps its env as sent.
    List<String> run = calls.get(3);
    assertEquals("run", run.get(0));
    assertEquals(DOCUMENT, envValue(run, "QITS_CI_REGISTRY_AUTH_CONFIG"));
  }

  @Test
  void aFailedPullUnderALoginRemovesItAndNeverEchoesIt() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("image-inspect", 1, "", "")
            .answer(
                "pull", 1, "", "unauthorized: authentication required (" + AUTH + ") " + DOCUMENT);
    LaunchFailed failed =
        assertInstanceOf(
            LaunchFailed.class,
            launcher(fake)
                .launch(
                    new Launch(
                        "run-1",
                        0,
                        plainWithEnv(IMAGE, Map.of("QITS_CI_REGISTRY_AUTH_CONFIG", DOCUMENT)))));

    assertTrue(failed.detail().contains("unauthorized: authentication required"), failed::detail);
    assertTrue(failed.detail().contains(IMAGE), failed::detail);
    assertFalse(failed.detail().contains(AUTH), failed::detail);
    assertFalse(failed.detail().contains("auths"), failed::detail);
    List<String> pull = fake.calls().get(1);
    assertEquals(List.of("--config"), pull.subList(0, 1), () -> "pull: " + pull);
    Path configDir = Path.of(pull.get(1));
    assertFalse(Files.exists(configDir), "a failed pull strands no login: " + configDir);
    assertEquals(0, fake.calls("run").size());
  }

  @Test
  void aSpecWithNoRegistryLoginPullsUnderTheHostsConfigAsBefore() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("image-inspect", 1, "", "No such image")
            .answer("run", 0, "cid\n", "");
    assertInstanceOf(
        Launched.class,
        launcher(fake).launch(new Launch("run-1", 0, plainWithEnv(IMAGE, Map.of("CI", "true")))));

    List<List<String>> calls = fake.calls();
    assertTrue(calls.stream().noneMatch(c -> c.contains("--config")), () -> "calls: " + calls);
    assertEquals(List.of("pull", IMAGE), fake.calls().get(1));
    assertEquals(List.of(), fake.configs());
  }

  @Test
  void aPlainStepWithNoNetworkNamedJoinsNoNetworkAndIsGivenNoExtraHost() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("run", 0, "cid\n", "");
    launcher(fake).launch(new Launch("run-1", 0, plainWithEnv(IMAGE, Map.of())));
    List<String> step = fake.calls("run").getFirst();
    // Docker's default bridge: no qits-net, no runner build network, no platform name to resolve.
    assertFalse(step.contains("--network"), () -> "step: " + step);
    assertTrue(step.stream().noneMatch(a -> a.startsWith("--add-host")), () -> "step: " + step);
  }

  @Test
  void theRedactionTakesOutTheDocumentAndEveryLoginInIt() {
    assertEquals(
        "no [redacted] and no [redacted]",
        Launcher.redact("no " + DOCUMENT + " and no " + AUTH, DOCUMENT));
    assertEquals("as is", Launcher.redact("as is", null));
    assertEquals("not json", Launcher.redact("not json", "{broken"));
  }

  @Test
  void aLaunchRecordsTheImageItUsedInTheStateDirectory() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir.resolve("docker"))
            .answer("image-inspect", 1, "", "No such image")
            .answer("run", 0, "cid\n", "");
    Path file = dir.resolve("state").resolve(StepImages.FILE);
    StepImages images = new StepImages(file, () -> 1_234L);
    Launcher launcher =
        new Launcher(
            fake.docker(10),
            fake.binary,
            "r1",
            new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0"),
            images);

    assertInstanceOf(
        Launched.class, launcher.launch(new Launch("run-1", 0, WorkloadSpec.of("alpine:3", "c0"))));

    assertEquals(Map.of("alpine:3", 1_234L), images.record());
    assertEquals(
        Map.of("alpine:3", 1_234L), new StepImages(file, () -> 0L).record(), "and on disk");
  }

  @Test
  void aLaunchWhosePullFailsRecordsNothing() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir.resolve("docker"))
            .answer("image-inspect", 1, "", "")
            .answer("pull", 1, "", "manifest unknown");
    StepImages images = new StepImages(null, () -> 1L);
    Launcher launcher =
        new Launcher(
            fake.docker(10),
            fake.binary,
            "r1",
            new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0"),
            images);

    assertInstanceOf(
        LaunchFailed.class, launcher.launch(new Launch("run-1", 0, WorkloadSpec.of("nope:1", "c0"))));
    assertEquals(Map.of(), images.record());
  }
}

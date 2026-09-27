package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cirunner.protocol.Launch;
import eu.wohlben.qits.cirunner.protocol.LaunchFailed;
import eu.wohlben.qits.cirunner.protocol.Launched;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
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
    assertEquals("run", calls.get(2).get(0));
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
}

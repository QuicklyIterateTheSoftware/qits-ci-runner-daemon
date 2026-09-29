package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The argv IS the sandbox, so it is asserted element for element — qits-containers' DockerArgvTest
 * discipline. A flag lost here is invisible everywhere else until it is invisible in production.
 */
class RunnerArgvTest {

  /** What qits-ci's buildWorkloadSpec sends for an ordinary sandboxed step, less the env bulk. */
  private static WorkloadSpec step(boolean socket, String network) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_CI_SHA", "abc");
    env.put("CI", "true");
    return new WorkloadSpec(
        "registry.example:8080/qits/build-images/ci-base:latest",
        List.of("/bin/sh"),
        List.of("-c", "set -e\nexec /tmp/qits-ci-daemon"),
        env,
        Map.of("qits.ci.run", "run-1"),
        network,
        List.of("host.docker.internal:host-gateway"),
        null,
        socket,
        true,
        true,
        "4g",
        "4g",
        4096L,
        "4",
        1000,
        "qits-ci-run-1-5f3a-0",
        false);
  }

  @Test
  void anOrdinaryStepRendersTheWholeSandboxInAFixedOrder() {
    List<String> argv =
        RunnerArgv.run("docker", "r1", "run-1", step(false, "qits-net"), List.of("qits-net"),
            step(false, "qits-net").env());
    assertEquals(
        List.of(
            "docker", "run", "-d",
            "--name", "qits-ci-run-1-5f3a-0",
            "--network", "qits-net",
            "--add-host=host.docker.internal:host-gateway",
            "--label", "qits.ci.run=run-1",
            "--label", "qits.ci.runner=r1",
            "--label", "qits.ci.runner.run=run-1",
            "--security-opt=no-new-privileges",
            "--cap-drop=ALL",
            "--memory", "4g",
            "--memory-swap", "4g",
            "--pids-limit", "4096",
            "--cpus", "4",
            "--oom-score-adj", "1000",
            "-e", "CI=true",
            "-e", "QITS_CI_SHA=abc",
            "--entrypoint", "/bin/sh",
            "registry.example:8080/qits/build-images/ci-base:latest",
            "-c", "set -e\nexec /tmp/qits-ci-daemon"),
        argv);
  }

  @Test
  void noNetworkIsRenderedWhenTheSpecNamesNone() {
    List<String> argv =
        RunnerArgv.run("docker", "r1", "run-1", step(false, null), List.of(), step(false, null).env());
    assertFalse(argv.contains("--network"), argv::toString);
  }

  @Test
  void theDockerSocketIsBoundOnlyWhenTheSpecDeclaredIt() {
    String bind = "/var/run/docker.sock:/var/run/docker.sock";
    assertFalse(
        RunnerArgv.run("docker", "r1", "run-1", step(false, null), List.of(), Map.of()).contains(bind));
    List<String> argv =
        RunnerArgv.run("docker", "r1", "run-1", step(true, null), List.of(), Map.of());
    int v = argv.indexOf(bind);
    assertTrue(v > 0 && argv.get(v - 1).equals("-v"), argv::toString);
  }

  @Test
  void anUnsetSandboxRendersNoFlagsSoUnsetStaysDifferentFromOff() {
    List<String> argv =
        RunnerArgv.run(
            "docker", "r1", "run-1", WorkloadSpec.of("alpine:3", "c1"), List.of(), Map.of());
    assertEquals(
        List.of(
            "docker", "run", "-d", "--name", "c1",
            "--label", "qits.ci.runner=r1",
            "--label", "qits.ci.runner.run=run-1",
            "alpine:3"),
        argv);
  }

  @Test
  void twoNetworksRenderTwoFlagsInTheOrderTheLauncherChose() {
    List<String> argv =
        RunnerArgv.run(
            "docker", "r1", "run-1", step(false, "qits-net"), List.of("qits-net", "qits-ci-runner"),
            Map.of());
    assertEquals("qits-net", argv.get(argv.indexOf("--network") + 1));
    assertEquals("qits-ci-runner", argv.get(argv.lastIndexOf("--network") + 1));
  }

  @Test
  void aUserIsRenderedOnlyWhenNamed() {
    WorkloadSpec base = WorkloadSpec.of("alpine:3", "c1");
    WorkloadSpec asUser =
        new WorkloadSpec(
            base.image(), null, null, null, null, null, null, "1000:1000", false, false, false,
            null, null, null, null, null, base.name(), false);
    List<String> argv = RunnerArgv.run("docker", "r1", "run-1", asUser, List.of(), Map.of());
    assertEquals("1000:1000", argv.get(argv.indexOf("--user") + 1));
  }

  @Test
  void aSenderLabelInsideTheRunnersNamespaceIsRefusedBecauseItWouldSteerTheSweep() {
    WorkloadSpec forged =
        new WorkloadSpec(
            "alpine:3", null, null, null, Map.of("qits.ci.runner", "someone-else"), null, null,
            null, false, false, false, null, null, null, null, null, "c1", false);
    assertThrows(
        IllegalArgumentException.class,
        () -> RunnerArgv.run("docker", "r1", "run-1", forged, List.of(), Map.of()));
    WorkloadSpec nested =
        new WorkloadSpec(
            "alpine:3", null, null, null, Map.of("qits.ci.runner.run", "x"), null, null, null,
            false, false, false, null, null, null, null, null, "c1", false);
    assertThrows(
        IllegalArgumentException.class,
        () -> RunnerArgv.run("docker", "r1", "run-1", nested, List.of(), Map.of()));
  }

  @Test
  void valuesThatCouldBeReadAsFlagsOrSpanLinesAreRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RunnerArgv.run(
                "docker", "r1", "run-1", WorkloadSpec.of("--privileged", "c1"), List.of(), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RunnerArgv.run(
                "docker", "r1", "run-1", WorkloadSpec.of("alpine:3", "-c1"), List.of(), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RunnerArgv.run(
                "docker", "r1", "run-1", WorkloadSpec.of("alpine:3", "c1"), List.of(),
                Map.of("BAD KEY", "x")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RunnerArgv.run(
                "docker", "r1", "run-1", WorkloadSpec.of("alpine:3", null), List.of(), Map.of()),
        "a container with no name could never be reaped");
  }

  @Test
  void theSweepAndTheCancelSelectByTheRunnersOwnLabel() {
    assertEquals(
        List.of("docker", "ps", "-aq", "--filter", "label=qits.ci.runner=r1"),
        RunnerArgv.psOwn("docker", "r1"));
    assertEquals(
        List.of(
            "docker", "ps", "-aq",
            "--filter", "label=qits.ci.runner=r1",
            "--filter", "label=qits.ci.runner.run=run-1"),
        RunnerArgv.psRun("docker", "r1", "run-1"));
  }

  // ---- the runner's own container ---------------------------------------------------------------

  private static SelfSpec self(Map<String, String> labels) {
    return new SelfSpec(
        "0123456789ab",
        "sha256:old",
        Map.of(
            "QITS_CI_RUNNER_URL", "https://ci.qits.example.eu",
            "QITS_CI_RUNNER_ID", "3f2a9c1e-0000-4000-8000-000000000001",
            "QITS_CI_RUNNER_SLOTS", "2",
            "QITS_CI_RUNNER_REGISTRATION_TOKEN", "qits_tok_SPENT"),
        labels,
        List.of(
            "/var/run/docker.sock:/var/run/docker.sock",
            "qits-ci-runner-state-3f2a9c1e:/var/lib/qits-ci-runner"),
        List.of(),
        "unless-stopped",
        "default");
  }

  @Test
  void theSuccessorIsTheContainerContractWithANewImageNameAndVersionAndNoToken() {
    assertEquals(
        List.of(
            "docker",
            "run",
            "-d",
            "--name",
            "qits-ci-runner-3f2a9c1e-2026.929.1",
            "--restart",
            "unless-stopped",
            "--label",
            "qits.ci.runner.process=3f2a9c1e-0000-4000-8000-000000000001",
            "--label",
            "qits.ci.runner.version=2026.929.1",
            "-v",
            "/var/run/docker.sock:/var/run/docker.sock",
            "-v",
            "qits-ci-runner-state-3f2a9c1e:/var/lib/qits-ci-runner",
            "-e",
            "QITS_CI_RUNNER_ID=3f2a9c1e-0000-4000-8000-000000000001",
            "-e",
            "QITS_CI_RUNNER_SLOTS=2",
            "-e",
            "QITS_CI_RUNNER_URL=https://ci.qits.example.eu",
            "registry.qits.example.eu/qits/qits-ci-runner:2026.929.1"),
        RunnerArgv.runSuccessor(
            "docker",
            "3f2a9c1e-0000-4000-8000-000000000001",
            "2026.929.1",
            "registry.qits.example.eu/qits/qits-ci-runner:2026.929.1",
            self(
                Map.of(
                    "qits.ci.runner.process", "3f2a9c1e-0000-4000-8000-000000000001",
                    "qits.ci.runner.version", "2026.928.93942"))));
  }

  /**
   * The boot sweep removes every container labelled {@code qits.ci.runner=<id>}. A runner container
   * that carried it would be removed by its own runner at every reconnect — so no runner container
   * ever does, even when the one it inherits from somehow did.
   */
  @Test
  void aRunnerContainerCanNeverMatchItsOwnSweep() {
    String sweep = RunnerArgv.psOwn("docker", "r1").getLast();
    assertEquals("label=qits.ci.runner=r1", sweep);
    List<String> successor =
        RunnerArgv.runSuccessor(
            "docker",
            "r1",
            "2",
            "r/qits/qits-ci-runner:2",
            self(Map.of("qits.ci.runner", "r1", "qits.ci.runner.run", "run-1", "keep", "me")));
    for (int i = 0; i < successor.size() - 1; i++) {
      if (successor.get(i).equals("--label")) {
        String label = successor.get(i + 1);
        assertFalse(label.startsWith("qits.ci.runner="), label);
        assertFalse(label.startsWith("qits.ci.runner.run="), label);
      }
    }
    assertTrue(successor.contains("keep=me"), "an operator's own label is carried");
    assertTrue(successor.contains("qits.ci.runner.process=r1"));
    assertFalse(("label=" + RunnerArgv.PROCESS_LABEL + "=r1").equals(sweep));
    assertEquals("label=qits.ci.runner.process=r1", RunnerArgv.psProcess("docker", "r1").getLast());
  }

  @Test
  void aSuccessorOnAnOperatorsNetworkStaysOnIt() {
    SelfSpec onNet =
        new SelfSpec("i", "sha256:old", Map.of(), Map.of(), List.of(), List.of("type=volume,source=x,target=/x"), "always", "qits-net");
    List<String> argv = RunnerArgv.runSuccessor("docker", "r1", "2", "r/i:2", onNet);
    assertEquals(
        List.of(
            "docker", "run", "-d", "--name", "qits-ci-runner-r1-2", "--restart", "always",
            "--network", "qits-net", "--label", "qits.ci.runner.process=r1", "--label",
            "qits.ci.runner.version=2", "--mount", "type=volume,source=x,target=/x", "r/i:2"),
        argv);
  }

  @Test
  void aVersionOutsideItsCharsetNamesNoContainer() {
    assertThrows(
        IllegalArgumentException.class, () -> RunnerArgv.containerName("r1", "2;rm -rf /"));
    assertThrows(IllegalArgumentException.class, () -> RunnerArgv.containerName("r1", ""));
    assertEquals("qits-ci-runner-abcdefgh-1.2", RunnerArgv.containerName("abcdefghijk", "1.2"));
  }

  @Test
  void aStepsLastWordsAreReadByNameAndBoundedByLine() {
    assertEquals(
        List.of("docker", "logs", "--tail", "200", "qits-ci-run-1-x-0"),
        RunnerArgv.logs("docker", "qits-ci-run-1-x-0"));
    assertEquals(
        List.of(
            "docker", "inspect", "--format", "{{.State.Status}} {{.State.ExitCode}}",
            "qits-ci-run-1-x-0"),
        RunnerArgv.exitState("docker", "qits-ci-run-1-x-0"));
    assertThrows(IllegalArgumentException.class, () -> RunnerArgv.logs("docker", "--follow"));
    assertThrows(IllegalArgumentException.class, () -> RunnerArgv.exitState("docker", "-f"));
  }

  @Test
  void theDecommissionHelperIsTheRunnersOwnImageInHelperModeWithTheSocketAndNothingElse() {
    assertEquals(
        List.of(
            "docker",
            "run",
            "-d",
            "--name",
            "qits-ci-runner-c4374992-decommission",
            "--network",
            "none",
            "--label",
            "qits.ci.runner.decommission=c4374992-0000-4000-8000-000000000000",
            "-v",
            "/var/run/docker.sock:/var/run/docker.sock",
            "-e",
            "QITS_CI_RUNNER_DECOMMISSION=c4374992-0000-4000-8000-000000000000",
            "-e",
            "QITS_CI_RUNNER_DECOMMISSION_VOLUME=qits-ci-runner-state-c4374992",
            "sha256:0123abcd"),
        RunnerArgv.runDecommissioner(
            "docker",
            "c4374992-0000-4000-8000-000000000000",
            "sha256:0123abcd",
            "qits-ci-runner-state-c4374992"));
    List<String> noVolume = RunnerArgv.runDecommissioner("docker", "r1", "sha256:0123abcd", null);
    assertTrue(noVolume.stream().noneMatch(a -> a.contains("VOLUME")), noVolume::toString);
    assertFalse(noVolume.contains("--rm"), "the helper removes itself only once it succeeded");
    assertFalse(
        noVolume.stream().anyMatch(a -> a.startsWith("qits.ci.runner.process") || a.startsWith("qits.ci.runner=")),
        "a helper carrying a label it removes by would remove itself mid-job");
    assertEquals(List.of("docker", "volume", "rm", "qits-ci-runner-state-r1"), RunnerArgv.volumeRm("docker", "qits-ci-runner-state-r1"));
    assertThrows(IllegalArgumentException.class, () -> RunnerArgv.volumeRm("docker", "-f"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RunnerArgv.runDecommissioner("docker", "r1", "sha256:x", "--privileged"));
  }
}

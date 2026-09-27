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
}

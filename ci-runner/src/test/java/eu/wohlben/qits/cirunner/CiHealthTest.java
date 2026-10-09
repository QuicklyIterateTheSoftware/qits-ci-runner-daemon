package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.runner.protocol.health.HealthReport;
import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.health.RunnerHealthCheck;
import eu.wohlben.qits.runner.toolkit.health.SessionFacts;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The registry, its order, its docker seam and its default step image. */
class CiHealthTest {

  @TempDir Path dir;

  @Test
  void theCiChecksFollowTheJavalibDefaultsInAFixedOrderWhateverOrderArcHandsThemIn() {
    List<RunnerHealthCheck> shuffled = new ArrayList<>(CiHealth.builtIn());
    shuffled.add(RunnerHealthCheck.named("aLaterCheck", null, ctx -> null));
    java.util.Collections.reverse(shuffled);
    assertEquals(
        List.of(
            "docker", "nodeInventory", "session", "buildkit", "network", "idRange", "stepImage",
            "aLaterCheck"),
        CiHealth.registry(Duration.ofSeconds(1), "r1", shuffled).names());
  }

  @Test
  void theDefaultStepImageIsOnTheRegistryTheCiUrlNames() {
    assertEquals(
        "registry.qits.example.eu/qits/build-images/ci-base:latest",
        CiHealth.defaultStepImage("https://ci.qits.example.eu"));
    assertEquals(
        "registry.qits.example.eu/qits/build-images/ci-base:latest",
        CiHealth.defaultStepImage("https://ci.qits.example.eu/"));
    assertEquals(
        "qits/build-images/ci-base:latest", CiHealth.defaultStepImage("http://qits-ci:8080"));
    assertEquals("qits/build-images/ci-base:latest", CiHealth.defaultStepImage(null));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aCallUnderAnotherDeadlineForksADockerOfThatDeadlineOnce() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("version", 0, "27.1.1\n", "");
    AtomicInteger forked = new AtomicInteger();
    DockerCommand docker =
        CiHealth.dockerCommand(
            fake.binary,
            fake.docker(10),
            10,
            seconds -> {
              forked.incrementAndGet();
              return fake.docker(seconds);
            });
    assertEquals("27.1.1\n", docker.run(List.of(fake.binary, "version")).stdout());
    assertEquals(0, forked.get(), "the default deadline is the runner's own docker");
    docker.run(List.of(fake.binary, "version"), Duration.ofSeconds(180));
    docker.run(List.of(fake.binary, "version"), Duration.ofSeconds(180));
    assertEquals(1, forked.get());
    assertEquals(Duration.ofSeconds(10), docker.deadline());
    fake.answer("version", 3, "", "nope");
    var failed = docker.run(List.of(fake.binary, "version"));
    assertFalse(failed.ok());
    assertEquals("nope", failed.detail());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aRequestWithoutAnImageChecksTheDefaultAndOneWithAnImageChecksThat() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    CiHealth health =
        new CiHealth(
            CiHealth.registry(Duration.ofSeconds(30), "r1", CiHealth.builtIn()),
            CiHealth.dockerCommand(fake.binary, fake.docker(10), 10, fake::docker),
            () -> new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0"),
            new Capabilities(true, "amd64", "linux", Map.of()),
            StepImages.inMemory(),
            "registry.example/qits/build-images/ci-base:latest");
    try {
      HealthReport report = health.check("q1", null, new SessionFacts(true, null, 2));
      assertEquals("q1", report.requestId());
      Map<String, Object> data = report.checks().getLast().data();
      assertEquals("registry.example/qits/build-images/ci-base:latest", data.get("image"));
      assertEquals(false, data.get("requested"));
      report = health.check("q2", "other/image:1", new SessionFacts(true, null, 2));
      assertEquals("other/image:1", report.checks().getLast().data().get("image"));
      assertEquals(true, report.checks().getLast().data().get("requested"));
    } finally {
      health.close();
    }
  }

  /**
   * The javalib's CI-shaped identity spells exactly what this daemon already puts on the node, so
   * {@code nodeInventory} finds the step containers and the runner's own container.
   */
  @Test
  void theCiIdentitySpellsThisDaemonsLabelsAndContainerName() {
    RunnerIdentity ci = CiRunnerIdentity.of("2026.1003.50846");
    String id = "1a2b3c4d-5e6f-7a8b-9c0d-112233445566";
    assertEquals(RunnerArgv.RUNNER_LABEL, ci.ownerLabel());
    assertEquals(RunnerArgv.RUN_LABEL, ci.workLabel());
    assertEquals(RunnerArgv.PROCESS_LABEL, ci.processLabel());
    assertEquals(RunnerArgv.VERSION_LABEL, ci.versionLabel());
    assertEquals(RunnerArgv.DECOMMISSION_LABEL, ci.decommissionLabel());
    assertEquals(Main.SELF_UPDATE_LABEL, ci.selfUpdateLabel());
    assertEquals(
        RunnerArgv.containerName(id, "2026.1003.50846"), ci.containerName(id, "2026.1003.50846"));
    assertEquals(RunnerEnv.DEFAULT_STATE_DIR, ci.defaultStateDir());
    assertTrue(ci.ownsName(BuildPlane.CONTAINER));
  }
}

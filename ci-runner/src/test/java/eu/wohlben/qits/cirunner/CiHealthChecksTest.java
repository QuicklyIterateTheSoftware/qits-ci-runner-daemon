package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.health.HealthContext;
import eu.wohlben.qits.runner.toolkit.health.RunnerHealthCheck;
import eu.wohlben.qits.runner.toolkit.health.SessionFacts;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * CI's own node health checks, each against the shell script answering like docker — every ok and
 * every failed branch, and that none of them writes to the node except the step image's pull.
 */
@EnabledOnOs(OS.LINUX)
class CiHealthChecksTest {

  private static final String BUILDKITD = BuildPlane.CONTAINER;
  private static final String IMAGE = "registry.example/qits/build-images/ci-base:latest";
  private static final Capabilities WIDE =
      new Capabilities(true, "amd64", "linux", Map.of(), Capabilities.FULL_ID_RANGE);

  @TempDir Path dir;
  private FakeDocker fake;
  private BuildPlane plane;
  private StepImages images;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakeDocker(dir);
    plane = new BuildPlane(fake.docker(10), fake.binary, "moby/buildkit:v0.33.0");
    images = StepImages.inMemory();
  }

  private HealthContext ctx(Capabilities caps, String image) {
    Map<Class<?>, Object> services = new LinkedHashMap<>();
    services.put(BuildPlane.class, plane);
    services.put(Capabilities.class, caps);
    services.put(StepImages.class, images);
    services.put(CiHealth.StepImageTarget.class, new CiHealth.StepImageTarget(image, true));
    DockerCommand docker =
        CiHealth.dockerCommand(fake.binary, fake.docker(10), 10, fake::docker);
    return HealthContext.of(docker, () -> new SessionFacts(true, null, 1), services);
  }

  private RunnerHealthCheck.Result run(RunnerHealthCheck check) throws Exception {
    return check.run(ctx(WIDE, IMAGE));
  }

  private void noBuilder() throws Exception {
    fake.answerFor("inspect", BUILDKITD, 1, "", "Error: No such object: " + BUILDKITD);
  }

  private void noNetwork() throws Exception {
    fake.answer("network-inspect", 1, "", "Error response from daemon: network qits-ci-runner not found");
  }

  /** Only reads: no create, start, run, rm, pull or connect. */
  private void assertReadOnly() throws Exception {
    for (List<String> call : fake.calls()) {
      String verb = call.getFirst().equals("network") ? "network " + call.get(1) : call.getFirst();
      assertTrue(
          List.of("inspect", "network inspect", "image", "version", "ps", "volume").contains(verb)
              && !call.contains("create"),
          "a health check wrote to the node: " + call);
    }
  }

  // --- buildkit ----------------------------------------------------------------------------------

  @Test
  void aBuilderNoBuildNeededYetIsOkAndNotStarted() throws Exception {
    noBuilder();
    RunnerHealthCheck.Result result = run(new BuildkitCheck());
    assertTrue(result.ok(), result.detail());
    assertTrue(result.detail().startsWith("not started yet"), result.detail());
    assertEquals("ABSENT", result.data().get("presence"));
    assertEquals(BuildPlane.ADDRESS, result.data().get("address"));
    assertEquals(plane.stamp(), result.data().get("configuredStamp"));
    assertReadOnly();
  }

  @Test
  void aRunningBuilderUnderTheConfiguredStampIsOk() throws Exception {
    fake.answerFor("inspect", BUILDKITD, 0, plane.stamp() + "|running\n", "");
    RunnerHealthCheck.Result result = run(new BuildkitCheck());
    assertTrue(result.ok(), result.detail());
    assertEquals("running", result.data().get("state"));
    assertEquals(plane.stamp(), result.data().get("stamp"));
    assertEquals(
        List.of(
            "inspect", "--format", "{{index .Config.Labels \"qits.ci.runner.buildkitd\"}}|{{.State.Status}}",
            BUILDKITD),
        fake.calls().getFirst());
  }

  @Test
  void aStoppedBuilderFailsAndIsNotStarted() throws Exception {
    fake.answerFor("inspect", BUILDKITD, 0, plane.stamp() + "|exited\n", "");
    RunnerHealthCheck.Result result = run(new BuildkitCheck());
    assertFalse(result.ok());
    assertTrue(result.detail().contains("is exited"), result.detail());
    assertReadOnly();
  }

  @Test
  void aStaleStampFails() throws Exception {
    fake.answerFor("inspect", BUILDKITD, 0, "0000000000000000|running\n", "");
    RunnerHealthCheck.Result result = run(new BuildkitCheck());
    assertFalse(result.ok());
    assertTrue(result.detail().contains("0000000000000000"), result.detail());
    assertTrue(result.detail().contains(plane.stamp()), result.detail());
    assertReadOnly();
  }

  @Test
  void somebodyElsesContainerUnderTheNameFails() throws Exception {
    fake.answerFor("inspect", BUILDKITD, 0, "<no value>|running\n", "");
    RunnerHealthCheck.Result result = run(new BuildkitCheck());
    assertFalse(result.ok());
    assertEquals("FOREIGN", result.data().get("presence"));
  }

  @Test
  void aDockerThatDoesNotAnswerFailsTheBuilderCheck() throws Exception {
    fake.answerFor("inspect", BUILDKITD, 1, "", "Cannot connect to the Docker daemon");
    RunnerHealthCheck.Result result = run(new BuildkitCheck());
    assertFalse(result.ok());
    assertTrue(result.detail().contains("Cannot connect"), result.detail());
  }

  // --- network -----------------------------------------------------------------------------------

  @Test
  void anExistingRunnerNetworkIsOk() throws Exception {
    fake.answer("network-inspect", 0, "bridge\n", "");
    RunnerHealthCheck.Result result = run(new NetworkCheck());
    assertTrue(result.ok(), result.detail());
    assertEquals("bridge", result.data().get("driver"));
    assertEquals(
        List.of("network", "inspect", "--format", "{{.Driver}}", BuildPlane.NETWORK),
        fake.calls().getFirst());
  }

  @Test
  void aMissingNetworkBeforeAnyBuilderIsNotNeededYet() throws Exception {
    noNetwork();
    noBuilder();
    RunnerHealthCheck.Result result = run(new NetworkCheck());
    assertTrue(result.ok(), result.detail());
    assertTrue(result.detail().startsWith("not needed yet"), result.detail());
    assertReadOnly();
  }

  @Test
  void aMissingNetworkUnderAnExistingBuilderFails() throws Exception {
    noNetwork();
    fake.answerFor("inspect", BUILDKITD, 0, plane.stamp() + "|running\n", "");
    RunnerHealthCheck.Result result = run(new NetworkCheck());
    assertFalse(result.ok());
    assertEquals("PRESENT", result.data().get("builder"));
    assertReadOnly();
  }

  @Test
  void aNetworkInspectDockerDoesNotAnswerFails() throws Exception {
    fake.answer("network-inspect", 1, "", "Cannot connect to the Docker daemon");
    assertFalse(run(new NetworkCheck()).ok());
  }

  // --- idRange -----------------------------------------------------------------------------------

  @Test
  void theFullIdSpaceIsOk() throws Exception {
    RunnerHealthCheck.Result result = new IdRangeCheck().run(ctx(WIDE, IMAGE));
    assertTrue(result.ok(), result.detail());
    assertEquals(Capabilities.FULL_ID_RANGE, result.data().get("idRange"));
    assertEquals(false, result.data().get("narrow"));
  }

  @Test
  void aNarrowRangeFailsWithTheBootWarningsWords() throws Exception {
    Capabilities narrow = new Capabilities(true, "amd64", "linux", Map.of(), 65537L);
    RunnerHealthCheck.Result result = new IdRangeCheck().run(ctx(narrow, IMAGE));
    assertFalse(result.ok());
    assertEquals(IdRange.narrowWarning(65537L), result.detail());
    assertEquals(true, result.data().get("narrow"));
  }

  @Test
  void anUnknownRangeIsOkAsTheHostAllowsIt() throws Exception {
    Capabilities unknown = new Capabilities(true, "amd64", "linux", Map.of());
    RunnerHealthCheck.Result result = new IdRangeCheck().run(ctx(unknown, IMAGE));
    assertTrue(result.ok(), result.detail());
    assertEquals(null, result.data().get("idRange"));
  }

  // --- stepImage ---------------------------------------------------------------------------------

  @Test
  void aPresentStepImageIsOkAndNeverPulled() throws Exception {
    RunnerHealthCheck.Result result = run(new StepImageCheck());
    assertTrue(result.ok(), result.detail());
    assertEquals(true, result.data().get("present"));
    assertEquals(false, result.data().get("pulled"));
    assertEquals(List.of(), fake.calls("pull"));
    assertEquals(
        List.of(List.of("image", "inspect", "--format", "{{.Id}}", IMAGE)), fake.calls("image"));
  }

  @Test
  void anAbsentStepImageIsPulledAndRecordedForHousekeeping() throws Exception {
    fake.answer("image-inspect", 1, "", "Error: No such image: " + IMAGE);
    RunnerHealthCheck.Result result = run(new StepImageCheck());
    assertTrue(result.ok(), result.detail());
    assertEquals(true, result.data().get("pulled"));
    assertEquals(List.of(List.of("pull", IMAGE)), fake.calls("pull"));
    assertTrue(images.record().containsKey(IMAGE));
  }

  @Test
  void aPullTheRegistryRefusesFailsWithDockersWords() throws Exception {
    fake.answer("image-inspect", 1, "", "Error: No such image: " + IMAGE);
    fake.answer("pull", 1, "", "unauthorized: authentication required");
    RunnerHealthCheck.Result result = run(new StepImageCheck());
    assertFalse(result.ok());
    assertTrue(result.detail().contains("unauthorized"), result.detail());
    assertTrue(result.detail().contains("not a run's"), result.detail());
    assertFalse(images.record().containsKey(IMAGE));
  }

  @Test
  void anImageTheBeltsRefuseFailsBeforeAnyDockerCall() throws Exception {
    RunnerHealthCheck.Result result = new StepImageCheck().run(ctx(WIDE, "-rf /"));
    assertFalse(result.ok());
    assertTrue(result.detail().startsWith("refused by the runner"), result.detail());
    assertEquals(List.of(), fake.calls());
  }

  @Test
  void theStepImageCheckHasItsOwnLongerDeadlineAndThePullRunsUnderIt() {
    StepImageCheck check = new StepImageCheck();
    assertTrue(check.deadline().compareTo(StepImageCheck.PULL_DEADLINE) > 0);
    assertTrue(
        check.deadline().compareTo(Duration.ofMinutes(5)) < 0,
        "the host stops waiting for an answer after five minutes");
  }
}

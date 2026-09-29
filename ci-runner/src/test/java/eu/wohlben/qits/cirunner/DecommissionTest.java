package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The decommission helper's job against a shell script answering like docker: every container of
 * the runner, waited for and removed, its step containers, its state volume, and itself last.
 */
@EnabledOnOs(OS.LINUX)
class DecommissionTest {

  private static final Path STATE = Path.of("/var/lib/qits-ci-runner");
  private static final String HELPER = "dddddddddddd";
  private static final Decommission.Settings FAST = new Decommission.Settings(500, 20, 3);

  @TempDir Path dockerDir;
  private FakeDocker docker;
  private final List<String> said = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    docker = new FakeDocker(dockerDir);
  }

  private int finish(String volume) {
    return Decommission.finish(
        docker.docker(10), docker.binary, "r1", volume, Optional.of(HELPER), FAST, said::add);
  }

  @Test
  void theHelperRemovesEveryRunnerContainerThenTheStepsThenTheVolumeThenItself() throws Exception {
    docker.answerFor(
        "ps",
        "label=qits.ci.runner.process=r1",
        0,
        "aaaaaaaaaaaa|2026.928.1|running\nbbbbbbbbbbbb|2026.928.2|exited\n",
        "");
    docker.answerFor("ps", "label=qits.ci.runner=r1", 0, "step1\nstep2\n", "");
    docker.answerFor("inspect", "aaaaaaaaaaaa", 0, "exited\n", "");
    docker.answerFor("inspect", "bbbbbbbbbbbb", 0, "exited\n", "");

    assertEquals(0, finish("qits-ci-runner-state-r1"), () -> String.join("\n", said));

    List<List<String>> calls = ReaperTest.withoutReads(docker.calls());
    List<List<String>> removals =
        calls.stream().filter(c -> c.getFirst().equals("rm") || c.getFirst().equals("volume")).toList();
    assertEquals(
        List.of(
            List.of("rm", "-f", "aaaaaaaaaaaa"),
            List.of("rm", "-f", "bbbbbbbbbbbb"),
            List.of("rm", "-f", "step1"),
            List.of("rm", "-f", "step2"),
            List.of("volume", "rm", "qits-ci-runner-state-r1"),
            List.of("rm", "-f", HELPER)),
        removals);
    int waited = docker.calls().indexOf(List.of("inspect", "--format", "{{.State.Status}}", "aaaaaaaaaaaa"));
    assertTrue(
        waited >= 0 && waited < docker.calls().indexOf(List.of("rm", "-f", "aaaaaaaaaaaa")),
        "a running runner is waited for before it is removed");
  }

  @Test
  void aVolumeStillInUseIsRetriedAndOneThatStaysLeavesTheHelperStanding() throws Exception {
    docker.answer("volume-rm", 1, "", "Error response from daemon: remove x: volume is in use");

    assertEquals(1, finish("qits-ci-runner-state-r1"));
    assertEquals(3, docker.calls("volume").size(), "retried, since a volume is released late");
    assertFalse(
        docker.calls().contains(List.of("rm", "-f", HELPER)),
        "a helper that failed stays, with its log");
  }

  @Test
  void anAbsentVolumeIsAsGoodAsARemovedOne() throws Exception {
    docker.answer("volume-rm", 1, "", "Error response from daemon: get x: no such volume");
    assertEquals(0, finish("qits-ci-runner-state-r1"));
    assertTrue(docker.calls().contains(List.of("rm", "-f", HELPER)));
  }

  @Test
  void withNoVolumeNamedNoVolumeIsTouched() throws Exception {
    assertEquals(0, finish(null));
    assertTrue(docker.calls("volume").isEmpty());
  }

  @Test
  void theStateVolumeIsReadFromTheMountAtTheStateDirectory() {
    String mounts =
        new JsonArray()
            .add(
                new JsonObject()
                    .put(
                        "Mounts",
                        new JsonArray()
                            .add(
                                new JsonObject()
                                    .put("Type", "bind")
                                    .put("Source", "/var/run/docker.sock")
                                    .put("Destination", "/var/run/docker.sock"))
                            .add(
                                new JsonObject()
                                    .put("Type", "volume")
                                    .put("Name", "my-state")
                                    .put("Destination", STATE.toString()))))
            .encode();
    assertEquals(Optional.of("my-state"), Decommission.stateVolume(mounts, STATE));

    String bindsOnly =
        new JsonArray()
            .add(
                new JsonObject()
                    .put(
                        "HostConfig",
                        new JsonObject()
                            .put(
                                "Binds",
                                List.of(
                                    "/var/run/docker.sock:/var/run/docker.sock",
                                    "qits-ci-runner-state-r1:/var/lib/qits-ci-runner"))))
            .encode();
    assertEquals(
        Optional.of("qits-ci-runner-state-r1"), Decommission.stateVolume(bindsOnly, STATE));

    String hostPath =
        new JsonArray()
            .add(
                new JsonObject()
                    .put(
                        "HostConfig",
                        new JsonObject()
                            .put("Binds", List.of("/srv/runner:/var/lib/qits-ci-runner"))))
            .encode();
    assertEquals(
        Optional.empty(),
        Decommission.stateVolume(hostPath, STATE),
        "a host directory is the operator's, never the runner's to delete");

    String declared =
        new JsonArray()
            .add(
                new JsonObject()
                    .put(
                        "HostConfig",
                        new JsonObject()
                            .put(
                                "Mounts",
                                new JsonArray()
                                    .add(
                                        new JsonObject(
                                            Map.of(
                                                "Type", "volume",
                                                "Source", "declared-state",
                                                "Target", STATE.toString()))))))
            .encode();
    assertEquals(Optional.of("declared-state"), Decommission.stateVolume(declared, STATE));
    assertEquals(Optional.empty(), Decommission.stateVolume("[{}]", STATE));
  }
}

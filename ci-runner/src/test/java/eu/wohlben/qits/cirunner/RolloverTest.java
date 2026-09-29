package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cirunner.protocol.Retire;
import eu.wohlben.qits.cirunner.protocol.Upgrade;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Rollover#pull} in isolation: the self-update image pull is the runner's own commissioned
 * client pair, never the short-lived bearer it dials the control socket with — see the class
 * javadoc there for why a JWT is refused by the edge's docker realm.
 */
@EnabledOnOs(OS.LINUX)
class RolloverTest {

  private static final ClientCredentials CLIENT =
      new ClientCredentials(
          "ci-runner-r1",
          "s3cr3t-client-secret",
          "https://idp.example/token",
          "aud",
          "wss://ci.example/socket",
          "hash");

  private static final IntSupplier NO_RUNS_HELD = () -> 0;

  private Rollover rollover(FakeDocker docker) {
    return new Rollover(
        docker.docker(10),
        docker.binary,
        "r1",
        "2026.1.1",
        Optional.empty(),
        new Rollover.Settings(100, 400, 3_000, 3_000, 50),
        CLIENT,
        NO_RUNS_HELD);
  }

  @Test
  void aPullLogsInWithTheRunnersOwnClientPairNeverAJwt(@TempDir Path dir) throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker.answer("pull", 0, "", "");
    Rollover rollover = rollover(docker);

    Optional<String> failure =
        rollover.pull(new Upgrade("2026.1.2", "registry.example:5000/qits/qits-ci-runner:2026.1.2", null));

    assertTrue(failure.isEmpty(), failure::toString);
    List<String> config = docker.configs().getFirst();
    String expectedAuth =
        Base64.getEncoder()
            .encodeToString(
                (CLIENT.clientId() + ":" + CLIENT.secret()).getBytes(StandardCharsets.UTF_8));
    JsonObject document = new JsonObject(config.get(1));
    String actualAuth =
        document.getJsonObject("auths").getJsonObject("registry.example:5000").getString("auth");
    assertEquals(expectedAuth, actualAuth);
    assertFalse(
        new String(Base64.getDecoder().decode(actualAuth), StandardCharsets.UTF_8).startsWith("token:"),
        "the login is the client pair, not a bearer token");

    List<String> pull =
        docker.calls().stream().filter(c -> c.contains("pull")).findFirst().orElseThrow();
    assertFalse(Files.exists(Path.of(pull.get(1))), "the throwaway config directory is removed");
  }

  @Test
  void aPullFailureNeverEchoesTheClientSecret(@TempDir Path dir) throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker.answer(
        "pull",
        1,
        "",
        "unauthorized: the identity provider refused these credentials for " + CLIENT.secret());
    Rollover rollover = rollover(docker);

    Optional<String> failure =
        rollover.pull(new Upgrade("2026.1.2", "registry.example:5000/qits/qits-ci-runner:2026.1.2", null));

    assertTrue(failure.isPresent());
    assertFalse(failure.get().contains(CLIENT.secret()), failure::get);
    assertTrue(failure.get().contains("[redacted]"), failure::get);
  }

  @Test
  void aSupersededRetirementNeverTouchesTheNodesBuilder(@TempDir Path dir) throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    Rollover rollover =
        new Rollover(
            docker.docker(10),
            docker.binary,
            "r1",
            "2026.1.1",
            Optional.of("selfid"),
            new Rollover.Settings(100, 400, 3_000, 3_000, 50),
            CLIENT,
            NO_RUNS_HELD);

    rollover.leave();
    // No upgrade was ever asked for, so this is the operator's own retirement — the SUPERSEDED path.
    rollover.retire(new Retire("operator retirement", Retire.Kind.SUPERSEDED));

    List<List<String>> calls = docker.calls();
    assertTrue(
        calls.stream()
            .noneMatch(
                call ->
                    call.contains(BuildPlane.CONTAINER)
                        || call.contains(BuildPlane.STATE_VOLUME)
                        || call.contains(BuildPlane.NETWORK)),
        () -> "rollover must never touch the shared builder: " + calls);
  }

  private static final String OLD_IMAGE = "registry.example:5000/qits/qits-ci-runner:2026.1.0";

  private Rollover inContainer(FakeDocker docker) {
    return new Rollover(
        docker.docker(10),
        docker.binary,
        "r1",
        "2026.1.1",
        Optional.of("0123456789ab"),
        new Rollover.Settings(100, 400, 3_000, 3_000, 50),
        CLIENT,
        NO_RUNS_HELD);
  }

  @Test
  void theSuccessorRemovesItsPredecessorsImageOnceTheContainerIsGone(@TempDir Path dir)
      throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker.answerFor(
        "ps",
        "label=qits.ci.runner.process=r1",
        0,
        "0123456789ab|2026.1.1|running|registry.example:5000/qits/qits-ci-runner:2026.1.1\n"
            + "aaaaaaaaaaaa|2026.1.0|exited|" + OLD_IMAGE + "\n",
        "");
    docker.answerFor("inspect", "0123456789ab", 0, "sha256:own\n", "");
    docker.answerFor("image-inspect", OLD_IMAGE, 0, "sha256:old\n", "");

    inContainer(docker).removePredecessors();

    List<List<String>> calls = docker.calls();
    int removedContainer = calls.indexOf(List.of("rm", "-f", "aaaaaaaaaaaa"));
    int removedImage = calls.indexOf(List.of("image", "rm", OLD_IMAGE));
    assertTrue(removedContainer >= 0, calls::toString);
    assertTrue(removedImage > removedContainer, () -> "the image goes after its container: " + calls);
    assertTrue(
        calls.contains(List.of("ps", "-a", "-q", "--filter", "ancestor=sha256:old")),
        () -> "it asks whether any container still uses it: " + calls);
    assertEquals(1, docker.calls("image").stream().filter(c -> c.get(1).equals("rm")).count());
  }

  @Test
  void aPredecessorImageDockerRefusesToRemoveIsLeftAndNothingFails(@TempDir Path dir)
      throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker.answerFor(
        "ps",
        "label=qits.ci.runner.process=r1",
        0,
        "aaaaaaaaaaaa|2026.1.0|exited|" + OLD_IMAGE + "\n",
        "");
    docker.answerFor("inspect", "0123456789ab", 0, "sha256:own\n", "");
    docker.answerFor("image-inspect", OLD_IMAGE, 0, "sha256:old\n", "");
    docker.answer("image-rm", 1, "", "conflict: unable to remove repository reference");

    inContainer(docker).removePredecessors();

    assertTrue(docker.calls().contains(List.of("image", "rm", OLD_IMAGE)));
  }

  @Test
  void aPredecessorOnTheRunningImageKeepsIt(@TempDir Path dir) throws Exception {
    FakeDocker docker = new FakeDocker(dir);
    docker.answerFor(
        "ps",
        "label=qits.ci.runner.process=r1",
        0,
        "aaaaaaaaaaaa|2026.1.0|exited|" + OLD_IMAGE + "\n",
        "");
    docker.answerFor("inspect", "0123456789ab", 0, "sha256:same\n", "");
    docker.answerFor("image-inspect", OLD_IMAGE, 0, "sha256:same\n", "");

    inContainer(docker).removePredecessors();

    assertTrue(docker.calls().contains(List.of("rm", "-f", "aaaaaaaaaaaa")));
    assertFalse(docker.calls().stream().anyMatch(c -> c.size() > 1 && c.get(1).equals("rm")
        && c.get(0).equals("image")), () -> "never the running image: " + docker.callsQuietly("image"));
  }
}

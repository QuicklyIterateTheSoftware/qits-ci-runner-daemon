package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
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
}

package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.cirunner.protocol.Reap;
import eu.wohlben.qits.cirunner.protocol.Reaped;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class ReaperTest {

  @TempDir Path dir;

  @Test
  void aReapRemovesTheNamedContainerAndIsAnswered() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    Reaped reaped =
        new Reaper(fake.docker(10), fake.binary, "r1").reap(new Reap("run-1", 3, "qits-ci-run-1-x-3"));
    assertEquals(new Reaped("run-1", 3), reaped);
    assertEquals(List.of(List.of("rm", "-f", "qits-ci-run-1-x-3")), fake.calls());
  }

  @Test
  void aReapIsAnsweredWhateverDockerSaid() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("rm", 1, "", "No such container");
    assertEquals(
        new Reaped("run-1", 0),
        new Reaper(fake.docker(10), fake.binary, "r1").reap(new Reap("run-1", 0, "gone")));
  }

  @Test
  void aReapOfANameThatWasNeverOursIsAnsweredWithoutAskingDocker() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    assertEquals(
        new Reaped("run-1", 0),
        new Reaper(fake.docker(10), fake.binary, "r1").reap(new Reap("run-1", 0, "--all")));
    assertEquals(List.of(), fake.calls());
  }

  @Test
  void aCancelRemovesTheRunsContainersFoundByBothLabels() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("ps", 0, "c0\nc1\n", "");
    int removed = new Reaper(fake.docker(10), fake.binary, "r1").cancel("run-1");
    assertEquals(2, removed);
    assertEquals(
        List.of(
            List.of(
                "ps", "-aq", "--filter", "label=qits.ci.runner=r1", "--filter",
                "label=qits.ci.runner.run=run-1"),
            List.of("rm", "-f", "c0"),
            List.of("rm", "-f", "c1")),
        fake.calls());
  }
}

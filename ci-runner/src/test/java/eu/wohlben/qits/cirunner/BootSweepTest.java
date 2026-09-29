package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sweep touches its own label and nothing else. "Nothing else" is the filter itself: the only
 * listing is by {@code qits.ci.runner=<id>}, and the only removals are of ids that listing answered.
 */
@EnabledOnOs(OS.LINUX)
class BootSweepTest {

  @TempDir Path dir;

  @Test
  void itListsByItsOwnLabelAndRemovesExactlyWhatThatListed() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("ps", 0, "aaa111\nbbb222\n", "");
    int removed = new BootSweep(fake.docker(10), fake.binary, "r1").sweep();

    assertEquals(2, removed);
    assertEquals(
        List.of(
            List.of("ps", "-aq", "--filter", "label=qits.ci.runner=r1"),
            List.of("rm", "-f", "aaa111"),
            List.of("rm", "-f", "bbb222")),
        ReaperTest.withoutReads(fake.calls()));
  }

  @Test
  void eachLeftoversOutputIsReadBeforeItIsRemoved() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("ps", 0, "aaa111\n", "")
            .answer("inspect", 0, "exited 2\n", "")
            .answer("logs", 0, "the step's last line\n", "");
    new BootSweep(fake.docker(10), fake.binary, "r1").sweep();
    assertEquals(
        List.of(
            List.of("inspect", "--format", "{{.State.Status}} {{.State.ExitCode}}", "aaa111"),
            List.of("logs", "--tail", "200", "aaa111"),
            List.of("rm", "-f", "aaa111")),
        fake.calls().subList(1, 4));
  }

  @Test
  void anotherRunnersIdSelectsAnotherLabel() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    new BootSweep(fake.docker(10), fake.binary, "r2").sweep();
    assertEquals(
        List.of("ps", "-aq", "--filter", "label=qits.ci.runner=r2"),
        fake.calls().getFirst());
  }

  @Test
  void aCarriedRunsContainersAreKeptAndEverythingElseIsSwept() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("ps+label_qits.ci.runner.run_run-a", 0, "live1\n", "")
            .answer("ps", 0, "live1\nstray1\n", "");
    int removed = new BootSweep(fake.docker(10), fake.binary, "r1").sweep(List.of("run-a"));

    assertEquals(1, removed);
    assertEquals(
        List.of(
            List.of(
                "ps", "-aq", "--filter", "label=qits.ci.runner=r1", "--filter",
                "label=qits.ci.runner.run=run-a"),
            List.of("ps", "-aq", "--filter", "label=qits.ci.runner=r1"),
            List.of("rm", "-f", "stray1")),
        ReaperTest.withoutReads(fake.calls()));
  }

  @Test
  void aCarriedRunThatCannotBeListedSweepsNothing() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("ps+label_qits.ci.runner.run_run-a", 1, "", "Cannot connect to the Docker daemon")
            .answer("ps", 0, "live1\n", "");
    assertEquals(0, new BootSweep(fake.docker(10), fake.binary, "r1").sweep(List.of("run-a")));
    assertEquals(0, fake.calls("rm").size());
  }

  @Test
  void nothingListedIsNothingRemoved() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    assertEquals(0, new BootSweep(fake.docker(10), fake.binary, "r1").sweep());
    assertEquals(0, fake.calls("rm").size());
  }

  @Test
  void aDockerThatDoesNotAnswerRemovesNothingAndDoesNotThrow() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("ps", 1, "", "Cannot connect to the Docker daemon");
    assertEquals(0, new BootSweep(fake.docker(10), fake.binary, "r1").sweep());
    assertEquals(0, fake.calls("rm").size());
  }
}

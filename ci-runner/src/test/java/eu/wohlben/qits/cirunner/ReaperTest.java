package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  private static final String NAME = "qits-ci-run-1-x-3";

  @TempDir Path dir;

  private static List<String> inspect(String name) {
    return List.of("inspect", "--format", "{{.State.Status}} {{.State.ExitCode}}", name);
  }

  private static List<String> logs(String name) {
    return List.of("logs", "--tail", "200", name);
  }

  @Test
  void aReapReadsTheContainersOutputBeforeRemovingIt() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("inspect", 0, "exited 1\n", "")
            .answer("logs", 0, "starting\n", "error: could not reach qits-ci\n");
    Reaped reaped = new Reaper(fake.docker(10), fake.binary, "r1").reap(new Reap("run-1", 3, NAME));

    assertEquals("run-1", reaped.runId());
    assertEquals(3, reaped.stepIndex());
    assertEquals(
        "[container exited 1]\nstarting\nerror: could not reach qits-ci\n",
        reaped.logTail(),
        "a first line for how it ended, then both streams");
    assertEquals(
        List.of(inspect(NAME), logs(NAME), List.of("rm", "-f", NAME)),
        fake.calls(),
        "the output is read before rm destroys it");
  }

  @Test
  void aContainerStillRunningSaysSoInsteadOfAnExitCode() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir).answer("inspect", 0, "running 0\n", "").answer("logs", 0, "dialling\n", "");
    Reaped reaped = new Reaper(fake.docker(10), fake.binary, "r1").reap(new Reap("run-1", 0, NAME));
    assertEquals("[container running]\ndialling\n", reaped.logTail());
  }

  @Test
  void anInspectThatFailsLeavesTheTailWithoutItsStateLine() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir).answer("inspect", 1, "", "boom").answer("logs", 0, "only this\n", "");
    Reaped reaped = new Reaper(fake.docker(10), fake.binary, "r1").reap(new Reap("run-1", 0, NAME));
    assertEquals("only this\n", reaped.logTail());
  }

  @Test
  void aLogsErrorIsANullTailAndTheReapGoesAhead() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("inspect", 0, "exited 0\n", "")
            .answer("logs", 1, "", "Error response from daemon: No such container: " + NAME);
    Reaped reaped = new Reaper(fake.docker(10), fake.binary, "r1").reap(new Reap("run-1", 0, NAME));
    assertEquals(new Reaped("run-1", 0, null), reaped);
    assertEquals(List.of("rm", "-f", NAME), fake.calls().getLast());
  }

  @Test
  void aLogsCallPastTheDeadlineIsANullTailAndTheReapGoesAhead() throws Exception {
    FakeDocker fake = new FakeDocker(dir).hang("logs", 30);
    Reaped reaped = new Reaper(fake.docker(1), fake.binary, "r1").reap(new Reap("run-1", 0, NAME));
    assertNull(reaped.logTail());
    assertEquals(List.of("rm", "-f", NAME), fake.calls().getLast());
  }

  @Test
  void theTailIsRedactedBeforeItLeaves() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer(
                "logs",
                0,
                "curl -H 'Authorization: Bearer abcdefghijklmnop' https://ci\n"
                    + "QITS_CI_RUNNER_REGISTRATION_TOKEN=qits_tok_s3cr3tvalue\n",
                "login password=hunter2hunter2 failed\n");
    String tail =
        new Reaper(fake.docker(10), fake.binary, "r1").reap(new Reap("run-1", 0, NAME)).logTail();
    assertFalse(tail.contains("abcdefghijklmnop"), tail);
    assertFalse(tail.contains("s3cr3tvalue"), tail);
    assertFalse(tail.contains("hunter2"), tail);
    assertTrue(tail.contains("Bearer [redacted]"), tail);
    assertTrue(tail.contains("password=[redacted]"), tail);
  }

  @Test
  void aLongOutputIsBoundedKeepingTheNewestLines() throws Exception {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < 4000; i++) {
      out.append("line ").append(i).append(" ").append("x".repeat(40)).append('\n');
    }
    FakeDocker fake = new FakeDocker(dir).answer("logs", 0, out.toString(), "");
    String tail =
        new Reaper(fake.docker(10), fake.binary, "r1").reap(new Reap("run-1", 0, NAME)).logTail();

    assertTrue(
        tail.length() <= LogTail.MAX_BYTES + LogTail.DROPPED.length() + 1,
        () -> "not bounded: " + tail.length());
    assertTrue(tail.startsWith(LogTail.DROPPED + "\nline "), () -> tail.substring(0, 80));
    assertTrue(tail.endsWith("line 3999 " + "x".repeat(40) + "\n"), "the newest line is kept");
    assertFalse(tail.contains("line 0 "), "the oldest lines are dropped first");
  }

  @Test
  void aReapIsAnsweredWhateverDockerSaid() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("rm", 1, "", "No such container");
    assertEquals(
        new Reaped("run-1", 0, ""),
        new Reaper(fake.docker(10), fake.binary, "r1").reap(new Reap("run-1", 0, "gone")));
  }

  @Test
  void aReapOfANameThatWasNeverOursIsAnsweredWithoutAskingDocker() throws Exception {
    FakeDocker fake = new FakeDocker(dir);
    assertEquals(
        new Reaped("run-1", 0, null),
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
        withoutReads(fake.calls()));
    assertEquals(
        List.of(inspect("c0"), logs("c0"), List.of("rm", "-f", "c0")),
        fake.calls().subList(1, 4),
        "a cancelled container's output is read before it goes, too");
  }

  /** The calls that change something — what a removal test is about. */
  static List<List<String>> withoutReads(List<List<String>> calls) {
    return calls.stream()
        .filter(c -> !c.getFirst().equals("inspect") && !c.getFirst().equals("logs"))
        .toList();
  }
}

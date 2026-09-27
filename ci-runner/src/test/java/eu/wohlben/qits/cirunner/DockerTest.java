package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The seam every docker call goes through, against a real process. */
@EnabledOnOs(OS.LINUX)
class DockerTest {

  @TempDir Path dir;

  @Test
  void stdoutAndStderrStayApart() throws Exception {
    FakeDocker fake = new FakeDocker(dir).answer("pull", 1, "progress…\n", "manifest unknown\n");
    Docker.Result result = fake.docker(10).run(List.of(fake.binary, "pull", "nope:1"));
    assertFalse(result.ok());
    assertEquals(1, result.exitCode());
    assertEquals("progress…\n", result.stdout());
    assertEquals("manifest unknown", result.detail(), "the diagnosis is stderr");
  }

  @Test
  void aCallPastItsDeadlineIsKilledAndSaysSo() throws Exception {
    FakeDocker fake = new FakeDocker(dir).hang("pull", 30);
    long started = System.nanoTime();
    Docker.Result result = fake.docker(1).run(List.of(fake.binary, "pull", "slow:1"));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    assertTrue(result.timedOut());
    assertFalse(result.ok());
    assertTrue(result.detail().contains("did not answer within 1s"), result::detail);
    assertTrue(elapsedMillis < 15_000, () -> "the deadline did not bound the call: " + elapsedMillis);
  }

  @Test
  void aMissingBinaryIsAnAnswerRatherThanAnException() {
    Docker.Result result =
        Docker.forking(5).run(List.of(dir.resolve("no-such-docker").toString(), "ps"));
    assertFalse(result.ok());
    assertTrue(result.detail().contains("could not run"), result::detail);
  }
}

package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code qits-ci-runner health}: the heartbeat file's age, as an exit code and one line. */
class HealthCommandTest {

  @TempDir Path state;

  private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

  private void beatAt(Instant when) throws Exception {
    Path file = state.resolve(HealthCommand.FILE);
    if (!Files.exists(file)) {
      Files.createFile(file);
    }
    Files.setLastModifiedTime(file, FileTime.from(when));
  }

  @Test
  void aFreshHeartbeatIsHealthy() throws Exception {
    beatAt(NOW.minusSeconds(10));
    HealthCommand.Verdict verdict = HealthCommand.check(state, NOW, Duration.ofSeconds(60));
    assertEquals(HealthCommand.HEALTHY, verdict.exitCode());
    assertEquals("healthy: last heartbeat 10s ago", verdict.reason());
  }

  @Test
  void aStaleHeartbeatIsUnhealthy() throws Exception {
    beatAt(NOW.minusSeconds(61));
    HealthCommand.Verdict verdict = HealthCommand.check(state, NOW, Duration.ofSeconds(60));
    assertEquals(HealthCommand.UNHEALTHY, verdict.exitCode());
    assertEquals("unhealthy: last heartbeat 61s ago (limit 60s)", verdict.reason());
  }

  @Test
  void theThresholdItselfIsStillHealthy() throws Exception {
    beatAt(NOW.minusSeconds(60));
    assertEquals(
        HealthCommand.HEALTHY, HealthCommand.check(state, NOW, Duration.ofSeconds(60)).exitCode());
  }

  @Test
  void aMissingHeartbeatIsUnhealthy() {
    HealthCommand.Verdict verdict = HealthCommand.check(state, NOW, Duration.ofSeconds(60));
    assertEquals(HealthCommand.UNHEALTHY, verdict.exitCode());
    assertTrue(verdict.reason().startsWith("unhealthy: no heartbeat at "), verdict.reason());
  }

  @Test
  void aMissingStateDirectoryIsExitTwo() {
    HealthCommand.Verdict verdict =
        HealthCommand.check(state.resolve("absent"), NOW, Duration.ofSeconds(60));
    assertEquals(HealthCommand.NO_STATE_DIR, verdict.exitCode());
    assertEquals(2, verdict.exitCode());
  }

  @Test
  void touchCreatesTheFileAndThenMovesItsMtime() throws Exception {
    Path file = state.resolve(HealthCommand.FILE);
    HealthCommand.touch(state);
    assertTrue(Files.exists(file));
    Files.setLastModifiedTime(file, FileTime.fromMillis(0));
    HealthCommand.touch(state);
    assertEquals(
        HealthCommand.HEALTHY,
        HealthCommand.check(state, Instant.now(), HealthCommand.MAX_AGE).exitCode());
  }

  @Test
  void runReadsTheStateDirAndPrintsOneLine() throws Exception {
    HealthCommand.touch(state);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int code = HealthCommand.run(state.toString(), new PrintStream(bytes, true, StandardCharsets.UTF_8));
    assertEquals(0, code);
    String out = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(out.startsWith("healthy: "), out);
    assertEquals(1, out.lines().count());

    bytes.reset();
    assertEquals(
        2,
        HealthCommand.run(
            state.resolve("absent").toString(),
            new PrintStream(bytes, true, StandardCharsets.UTF_8)));
  }
}

package eu.wohlben.qits.cirunner;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

/**
 * {@code qits-ci-runner health}: the container healthcheck a deployer-managed runner's {@code
 * health_cmd} would run, and the one reader of the heartbeat file the running process writes.
 *
 * <p><b>Healthy means connected to qits-ci.</b> The runner touches {@code <state dir>/heartbeat}
 * when the host acknowledges its {@code Hello} and on every {@code Heartbeat} it actually sends on a
 * connected socket (every 10 s), and never while it is disconnected. So a file younger than {@link
 * #MAX_AGE} is a runner that was talking to the CI a moment ago, and an older one is a runner that
 * is redialling, wedged or dead — whatever its process table says.
 *
 * <p>Answered before Quarkus starts and reading one file's mtime, because docker runs it every few
 * seconds for as long as the container lives. Only the explicit first argument {@code health}
 * selects it: the image's bare start, its version probe and its decommission helper are unchanged.
 */
final class HealthCommand {

  /** The first argument that selects this command. */
  static final String ARG = "health";

  /** The file under the state directory the runner touches while connected. */
  static final String FILE = "heartbeat";

  /** Six heartbeat intervals: a runner that missed this many is not connected. */
  static final Duration MAX_AGE = Duration.ofSeconds(60);

  /** The heartbeat is fresh: the runner was connected within {@link #MAX_AGE}. */
  static final int HEALTHY = 0;

  /** The heartbeat is missing or stale: not (yet, or any more) connected. */
  static final int UNHEALTHY = 1;

  /** The state directory itself does not exist — nothing was ever mounted or created there. */
  static final int NO_STATE_DIR = 2;

  /** An exit code and the one line printed beside it. */
  record Verdict(int exitCode, String reason) {}

  private HealthCommand() {}

  /** The command as {@link Main} runs it: read the environment, print the reason, return the code. */
  static int run(String stateDirEnv, PrintStream out) {
    Path stateDir =
        Path.of(RunnerEnv.blank(stateDirEnv) ? RunnerEnv.DEFAULT_STATE_DIR : stateDirEnv.strip());
    Verdict verdict = check(stateDir, Instant.now(), MAX_AGE);
    out.println(verdict.reason());
    return verdict.exitCode();
  }

  /** The decision, with the clock and the threshold handed in. */
  static Verdict check(Path stateDir, Instant now, Duration maxAge) {
    if (!Files.isDirectory(stateDir)) {
      return new Verdict(NO_STATE_DIR, "unhealthy: state directory " + stateDir + " does not exist");
    }
    Path file = stateDir.resolve(FILE);
    Instant beat;
    try {
      beat = Files.getLastModifiedTime(file).toInstant();
    } catch (NoSuchFileException e) {
      return new Verdict(UNHEALTHY, "unhealthy: no heartbeat at " + file + " (never connected)");
    } catch (IOException e) {
      return new Verdict(UNHEALTHY, "unhealthy: cannot read " + file + ": " + e.getMessage());
    }
    long age = Duration.between(beat, now).toSeconds();
    if (age > maxAge.toSeconds()) {
      return new Verdict(
          UNHEALTHY,
          "unhealthy: last heartbeat " + age + "s ago (limit " + maxAge.toSeconds() + "s)");
    }
    // A negative age is a clock step between two touches; the file was still written recently.
    return new Verdict(HEALTHY, "healthy: last heartbeat " + Math.max(0, age) + "s ago");
  }

  /** Create {@code <stateDir>/heartbeat}, or set its mtime to now. */
  static void touch(Path stateDir) throws IOException {
    Path file = stateDir.resolve(FILE);
    try {
      Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
    } catch (NoSuchFileException e) {
      Files.createFile(file);
    }
  }
}

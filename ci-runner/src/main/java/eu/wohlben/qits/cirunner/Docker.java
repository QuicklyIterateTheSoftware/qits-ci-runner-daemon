package eu.wohlben.qits.cirunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The one seam every docker call goes through: an argv in, an exit code and both streams out, <b>under
 * a deadline</b>. A docker CLI that hangs — a wedged daemon, a registry that stops answering mid-pull
 * — would otherwise hold a slot, a {@code Launch} answer or the boot sweep forever, and the host
 * cannot see why.
 *
 * <p>stdout and stderr are kept apart, unlike the step daemon's {@code CommandRunner}: docker writes
 * the one thing a caller reads (a container id, a list of ids) to stdout and its diagnosis to stderr,
 * and {@code LaunchFailed.detail} wants exactly the second. Both are bounded, keeping the tail, where
 * docker puts the line that matters.
 *
 * <p>The suite points {@link #forking} at a shell script that answers like docker, so every class
 * above this seam is tested against a real process rather than a fake of this interface.
 */
@FunctionalInterface
public interface Docker {

  /** How much of each stream is kept. A pull's progress is long; its last line is the verdict. */
  int MAX_CAPTURE = 8192;

  /** One invocation's outcome. {@code timedOut} is its own flag so a kill is not a mystery exit. */
  record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
    public boolean ok() {
      return exitCode == 0 && !timedOut;
    }

    /** What a human should read: stderr when there is any, else stdout, bounded. */
    public String detail() {
      String text = stderr == null || stderr.isBlank() ? stdout : stderr;
      return text == null ? "" : text.strip();
    }
  }

  Result run(List<String> argv);

  /**
   * The production runner. A spawn failure (no docker binary) is exit {@code -1} with the reason as
   * stderr, so a caller only ever branches on {@link Result#ok()}.
   */
  static Docker forking(long timeoutSeconds) {
    ExecutorService pumps =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "ci-runner-docker-pump");
              thread.setDaemon(true);
              return thread;
            });
    return argv -> {
      Process process;
      try {
        process = new ProcessBuilder(argv).start();
      } catch (IOException e) {
        return new Result(-1, "", "could not run " + argv.getFirst() + ": " + e.getMessage(), false);
      }
      try {
        // No stdin: nothing here is interactive, and a docker that waits on a terminal must see EOF.
        process.getOutputStream().close();
      } catch (IOException ignored) {
        // already closed is as good as closed
      }
      CompletableFuture<String> out =
          CompletableFuture.supplyAsync(() -> tail(process.getInputStream()), pumps);
      CompletableFuture<String> err =
          CompletableFuture.supplyAsync(() -> tail(process.getErrorStream()), pumps);
      try {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
          return new Result(
              -1,
              out.getNow(""),
              "docker " + String.join(" ", argv.subList(1, Math.min(argv.size(), 3)))
                  + " did not answer within " + timeoutSeconds + "s",
              true);
        }
        return new Result(
            process.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS), false);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
        return new Result(-1, "", "interrupted", false);
      } catch (Exception e) {
        return new Result(-1, "", String.valueOf(e.getMessage()), false);
      }
    };
  }

  /** Read a stream to its end, keeping at most {@link #MAX_CAPTURE} bytes of its tail. */
  private static String tail(InputStream in) {
    byte[] ring = new byte[MAX_CAPTURE];
    long total = 0;
    byte[] buffer = new byte[4096];
    try (in) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        for (int i = 0; i < read; i++) {
          ring[(int) (total++ % MAX_CAPTURE)] = buffer[i];
        }
      }
    } catch (IOException ignored) {
      // a stream closed by a kill ends the capture; what was read is still the tail
    }
    if (total <= MAX_CAPTURE) {
      return new String(ring, 0, (int) total, StandardCharsets.UTF_8);
    }
    int start = (int) (total % MAX_CAPTURE);
    byte[] ordered = new byte[MAX_CAPTURE];
    System.arraycopy(ring, start, ordered, 0, MAX_CAPTURE - start);
    System.arraycopy(ring, 0, ordered, MAX_CAPTURE - start, start);
    return new String(ordered, StandardCharsets.UTF_8);
  }
}

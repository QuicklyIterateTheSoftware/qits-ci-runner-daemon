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
   * {@link #run} with stderr folded into stdout as the process wrote them, keeping at most {@code
   * maxCapture} bytes of the tail. For {@code docker logs}, which replays a container's two streams
   * onto its own two: read apart they lose the order a person needs to follow a failure, and the
   * interleaving is only recoverable at the pipe. The default is for a caller that is not a
   * process, and can only concatenate.
   */
  default Result runMerged(List<String> argv, int maxCapture) {
    Result result = run(argv);
    String merged = (result.stdout() == null ? "" : result.stdout())
        + (result.stderr() == null ? "" : result.stderr());
    return new Result(result.exitCode(), merged, "", result.timedOut());
  }

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
    return new Docker() {
      @Override
      public Result run(List<String> argv) {
        return fork(argv, timeoutSeconds, pumps, false, MAX_CAPTURE);
      }

      @Override
      public Result runMerged(List<String> argv, int maxCapture) {
        return fork(argv, timeoutSeconds, pumps, true, maxCapture);
      }
    };
  }

  private static Result fork(
      List<String> argv,
      long timeoutSeconds,
      ExecutorService pumps,
      boolean merge,
      int maxCapture) {
    Process process;
    try {
      process = new ProcessBuilder(argv).redirectErrorStream(merge).start();
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
        CompletableFuture.supplyAsync(() -> tail(process.getInputStream(), maxCapture), pumps);
    // Merged, the error stream is a null stream that ends at once.
    CompletableFuture<String> err =
        CompletableFuture.supplyAsync(() -> tail(process.getErrorStream(), MAX_CAPTURE), pumps);
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
  }

  /** Read a stream to its end, keeping at most {@code max} bytes of its tail. */
  private static String tail(InputStream in, int max) {
    byte[] ring = new byte[max];
    long total = 0;
    byte[] buffer = new byte[4096];
    try (in) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        for (int i = 0; i < read; i++) {
          ring[(int) (total++ % max)] = buffer[i];
        }
      }
    } catch (IOException ignored) {
      // a stream closed by a kill ends the capture; what was read is still the tail
    }
    if (total <= max) {
      return new String(ring, 0, (int) total, StandardCharsets.UTF_8);
    }
    int start = (int) (total % max);
    byte[] ordered = new byte[max];
    System.arraycopy(ring, start, ordered, 0, max - start);
    System.arraycopy(ring, 0, ordered, max - start, start);
    return new String(ordered, StandardCharsets.UTF_8);
  }
}

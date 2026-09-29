package eu.wohlben.qits.cirunner;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import org.jboss.logging.Logger;

/**
 * The runner's disk: nothing else on a runner node collects garbage, so without this its docker
 * images grow forever. One pass once the runner is proven on the wire (the first {@code Ack}, after
 * the predecessors are gone) and then every {@link Settings#intervalMillis}:
 *
 * <ol>
 *   <li>runner images no container uses, other than the running one — {@link RunnerImages};
 *   <li>the builder, replaced when it runs a stale configuration and no run is held — {@link
 *       BuildPlane#refreshIfStale}, retried each pass until it has happened once;
 *   <li>recorded step images idle past {@link Settings#maxIdleMillis} and unused, then dangling
 *       images — {@link StepImages}.
 * </ol>
 *
 * The build cache itself is buildkitd's own garbage collector's, configured in {@link
 * BuildPlane#WORKER_TOML}.
 *
 * <p><b>Defaults in code, no knob.</b> Every six hours is often enough that a busy node never
 * accumulates more than a few hours of dangling layers and rarely enough that a quiet one is not
 * woken for nothing; a week of idleness means an image not used by any pipeline since the last
 * weekly one is the first to go, and pulling it back costs one pull.
 *
 * <p>Its own thread, never the socket's or a launch's: every call is a docker call under the docker
 * deadline, and a pass that takes minutes delays nothing. It never touches a running step: {@link
 * StepImages} explains the lock that keeps a launch and a sweep apart.
 */
public final class Housekeeping {

  private static final Logger LOG = Logger.getLogger(Housekeeping.class);

  /** Timings; {@link #defaults} is production's, and the suite shrinks them. */
  public record Settings(long intervalMillis, long maxIdleMillis) {

    /** A pass every 6h; a step image idle for 7 days is stale. */
    public static Settings defaults() {
      return new Settings(TimeUnit.HOURS.toMillis(6), TimeUnit.DAYS.toMillis(7));
    }
  }

  private final Docker docker;
  private final String dockerBinary;
  private final RunnerImages runnerImages;
  private final StepImages stepImages;
  private final Runnable refreshBuilder;
  private final Settings settings;
  private final AtomicBoolean started = new AtomicBoolean();
  private volatile boolean builderRefreshed;
  private volatile IntSupplier held = () -> 0;

  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "ci-runner-housekeeping");
            thread.setDaemon(true);
            return thread;
          });

  public Housekeeping(
      Docker docker,
      String dockerBinary,
      RunnerImages runnerImages,
      StepImages stepImages,
      Runnable refreshBuilder,
      Settings settings) {
    this.docker = docker;
    this.dockerBinary = dockerBinary;
    this.runnerImages = runnerImages;
    this.stepImages = stepImages;
    this.refreshBuilder = refreshBuilder;
    this.settings = settings;
  }

  /**
   * Begin: a pass now, then one every interval. Once per process; a later call changes nothing.
   *
   * @param held how many runs this runner holds — the builder is only replaced at zero
   */
  public void start(IntSupplier held) {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    this.held = held;
    try {
      executor.scheduleWithFixedDelay(
          this::pass, 0, settings.intervalMillis(), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException shutDown) {
      // The runner is on its way out; there is nothing left to keep.
    }
  }

  /** One pass. Failures are logged, never thrown: a thrown one would cancel the schedule. */
  void pass() {
    try {
      runnerImages.sweepLeftovers();
    } catch (RuntimeException e) {
      LOG.warnf("ci-runner's runner-image sweep failed: %s", e.getMessage());
    }
    try {
      if (!builderRefreshed && held.getAsInt() == 0) {
        refreshBuilder.run();
        builderRefreshed = true;
      }
    } catch (RuntimeException e) {
      LOG.warnf("ci-runner could not refresh its builder: %s", e.getMessage());
    }
    try {
      stepImages.sweep(docker, dockerBinary, settings.maxIdleMillis());
    } catch (RuntimeException e) {
      LOG.warnf("ci-runner's step-image sweep failed: %s", e.getMessage());
    }
  }

  public void shutdown() {
    executor.shutdownNow();
  }
}

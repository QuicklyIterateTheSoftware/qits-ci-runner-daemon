package eu.wohlben.qits.cirunner;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * The step images this runner pulled or ran, and the sweep that removes the stale ones. {@link
 * Launcher} pulls a step's image itself when the host lacks it, and nothing ever removed one, so a
 * node's disk grew with every image any run ever named.
 *
 * <p><b>The record is what the runner owns.</b> {@value #FILE} in the state directory maps each
 * image reference a launch used to when it last did (epoch millis). The sweep considers those
 * references and no other: the node is somebody's machine, and an image this runner never launched
 * is not this runner's to judge. It lives on the state volume so a rollover's successor inherits it.
 *
 * <p><b>Stale, unused, recorded — all three.</b> A reference is removed when its last use is older
 * than {@link Housekeeping.Settings#maxIdleMillis}, no container (running or exited) was created
 * from it, and {@code docker image rm} without {@code -f} agrees. Then {@code docker image prune -f}
 * — dangling images only, never {@code -a}, which would take every image nobody's container uses.
 *
 * <p><b>Why a lock rather than accepting the race.</b> A launch inspects the image, finds it
 * present, and then runs it; a sweep that removed it in between would fail that launch — docker's
 * refusal to remove an in-use image does not help, because no container uses it yet — or, worse,
 * make {@code docker run} pull it without the launch's registry login. So a launch holds {@link
 * #launching}'s read side from its inspect to its run (launches never exclude one another), and the
 * sweep takes the write side with {@code tryLock} per image: when a launch is in flight the image
 * is simply left for the next pass, so a launch never queues behind a sweep for longer than one
 * {@code docker image rm}, and the sweep never waits on a minute-long pull. The same applies to
 * the prune: an image pulled by digest has no tag and counts as dangling.
 */
public final class StepImages {

  private static final Logger LOG = Logger.getLogger(StepImages.class);

  /** The record's file name, in the runner's state directory. */
  public static final String FILE = "images.json";

  private final Path file;
  private final LongSupplier clock;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final Object saving = new Object();

  // Guarded by `this`.
  private final Map<String, Long> lastUsed = new LinkedHashMap<>();

  /** A record kept only in memory — for a launcher the suite builds without a state directory. */
  public static StepImages inMemory() {
    return new StepImages(null, System::currentTimeMillis);
  }

  /** @param file where the record lives; null keeps it in memory only */
  public StepImages(Path file, LongSupplier clock) {
    this.file = file;
    this.clock = clock;
    load();
  }

  /** Held for reading by a launch from its image inspect to its {@code docker run}. */
  Lock launching() {
    return lock.readLock();
  }

  /** A launch used {@code ref}: it is this runner's now, and fresh. */
  public void used(String ref) {
    synchronized (this) {
      lastUsed.put(ref, clock.getAsLong());
    }
    save();
  }

  /** A copy of the record, for the suite and the sweep. */
  synchronized Map<String, Long> record() {
    return new LinkedHashMap<>(lastUsed);
  }

  /**
   * One pass: remove every recorded image idle longer than {@code maxIdleMillis} that no container
   * uses, then the dangling images. Answers how many recorded images went. Never throws.
   */
  public int sweep(Docker docker, String dockerBinary, long maxIdleMillis) {
    int removed = 0;
    for (Map.Entry<String, Long> entry : record().entrySet()) {
      String ref = entry.getKey();
      if (clock.getAsLong() - entry.getValue() <= maxIdleMillis) {
        continue;
      }
      Lock exclusive = lock.writeLock();
      if (!exclusive.tryLock()) {
        LOG.debugf("ci-runner left %s for the next pass: a launch is in flight", ref);
        continue;
      }
      try {
        if (removeIfUnused(docker, dockerBinary, ref, maxIdleMillis)) {
          removed++;
        }
      } finally {
        exclusive.unlock();
      }
    }
    if (removed > 0) {
      LOG.infof(
          "ci-runner removed %d step image(s) unused for over %dh",
          removed, maxIdleMillis / 3_600_000);
    }
    Lock exclusive = lock.writeLock();
    if (exclusive.tryLock()) {
      try {
        Docker.Result pruned = docker.run(RunnerArgv.imagePruneDangling(dockerBinary));
        if (!pruned.ok()) {
          LOG.warnf("ci-runner could not prune dangling images: %s", pruned.detail());
        } else {
          String[] lines = pruned.stdout().strip().split("\\R");
          LOG.debugf("ci-runner pruned dangling images: %s", lines[lines.length - 1]);
        }
      } finally {
        exclusive.unlock();
      }
    }
    return removed;
  }

  /** Under the write lock: the record is re-read, so a launch that just used it keeps it. */
  private boolean removeIfUnused(Docker docker, String dockerBinary, String ref, long maxIdle) {
    synchronized (this) {
      Long last = lastUsed.get(ref);
      if (last == null || clock.getAsLong() - last <= maxIdle) {
        return false;
      }
    }
    try {
      Docker.Result present = docker.run(RunnerArgv.imageInspect(dockerBinary, ref));
      if (!present.ok()) {
        // Removed by somebody else: nothing left to own.
        forget(ref);
        return false;
      }
      Docker.Result users = docker.run(RunnerArgv.psAncestor(dockerBinary, ref));
      if (!users.ok()) {
        LOG.warnf(
            "ci-runner left %s: could not ask which containers use it: %s", ref, users.detail());
        return false;
      }
      if (!users.stdout().isBlank()) {
        return false;
      }
      Docker.Result gone = docker.run(RunnerArgv.imageRm(dockerBinary, ref));
      if (!gone.ok()) {
        LOG.warnf("ci-runner could not remove the step image %s: %s", ref, gone.detail());
        return false;
      }
      forget(ref);
      LOG.infof("ci-runner removed the step image %s", ref);
      return true;
    } catch (IllegalArgumentException notAnImage) {
      // A reference the belts refuse was never launched; it has no business in the record.
      forget(ref);
      return false;
    }
  }

  private void forget(String ref) {
    synchronized (this) {
      lastUsed.remove(ref);
    }
    save();
  }

  private synchronized void load() {
    if (file == null || !Files.exists(file)) {
      return;
    }
    try {
      JsonObject document = new JsonObject(Files.readString(file, StandardCharsets.UTF_8));
      for (String ref : document.fieldNames()) {
        if (document.getValue(ref) instanceof Number when) {
          lastUsed.put(ref, when.longValue());
        }
      }
    } catch (IOException | RuntimeException e) {
      // Losing the record costs only disk: its images become the node's, never removed by this.
      LOG.warnf("ci-runner could not read %s, and starts a new one: %s", file, e.getMessage());
    }
  }

  /** Written whole to a sibling, then moved over: a crash mid-write leaves the old record. */
  private void save() {
    if (file == null) {
      return;
    }
    // One writer at a time, and the snapshot taken inside: a later save never loses to an earlier.
    synchronized (saving) {
      String document;
      synchronized (this) {
        JsonObject json = new JsonObject();
        lastUsed.forEach(json::put);
        document = json.encodePrettily();
      }
      try {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(FILE + ".tmp");
        Files.writeString(temporary, document, StandardCharsets.UTF_8);
        Files.move(
            temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException | RuntimeException e) {
        LOG.warnf("ci-runner could not write %s: %s", file, e.getMessage());
      }
    }
  }
}

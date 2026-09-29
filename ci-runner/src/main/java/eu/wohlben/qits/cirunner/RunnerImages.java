package eu.wohlben.qits.cirunner;

import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The runner's own images, {@code <registry>/}{@value RunnerArgv#RUNNER_REPOSITORY}{@code
 * :<version>}: one is pulled per rollover and nothing else ever removes the one it replaced, so a
 * node that has seen twenty releases holds twenty runner images.
 *
 * <p><b>Two passes, both best-effort.</b> A successor removes its predecessor's image right after
 * removing the predecessor's container ({@link Rollover#removePredecessors}); and every housekeeping
 * pass ({@link Housekeeping}) removes any other runner image no container uses — the leftovers of
 * runners that rolled over before this existed, or of a removal that failed. A failure is a log
 * line and the next image, never an exception: disk is reclaimed eventually, and nothing about a
 * runner's work depends on it.
 *
 * <p><b>Never the running image.</b> Three belts, any one of which suffices: the image id this
 * process's own container runs is skipped by id; any image some container still uses — this one,
 * another runner's on the same node, a predecessor not yet gone — is skipped by {@code docker ps
 * --filter ancestor=}; and {@code docker image rm} without {@code -f} refuses an image a container
 * uses. A process that cannot tell which image is its own (not in a container, or its own {@code
 * docker inspect} failed) removes nothing at all.
 */
public final class RunnerImages {

  private static final Logger LOG = Logger.getLogger(RunnerImages.class);

  private final Docker docker;
  private final String dockerBinary;
  private final Optional<String> self;

  public RunnerImages(Docker docker, String dockerBinary, Optional<String> self) {
    this.docker = docker;
    this.dockerBinary = dockerBinary;
    this.self = self;
  }

  /**
   * Remove {@code ref} when it is a runner image no container uses and not this process's own.
   * Answers whether it was removed.
   */
  public boolean remove(String ref, String why) {
    Optional<String> own = ownImageId();
    if (own.isEmpty()) {
      return false;
    }
    return remove(ref, own.get(), why);
  }

  /**
   * One pass over every runner image on the node: remove each that is neither this process's own
   * nor used by any container. Answers how many went.
   */
  public int sweepLeftovers() {
    Optional<String> own = ownImageId();
    if (own.isEmpty()) {
      return 0;
    }
    Docker.Result listed = docker.run(RunnerArgv.imageList(dockerBinary), Docker.MAX_DOCUMENT);
    if (!listed.ok()) {
      LOG.warnf("ci-runner could not list the node's images: %s", listed.detail());
      return 0;
    }
    int removed = 0;
    for (String line : listed.stdout().split("\\R")) {
      String[] fields = line.strip().split("\\|", -1);
      if (fields.length < 2) {
        continue;
      }
      String ref = fields[0].strip();
      String id = fields[1].strip();
      int colon = ref.lastIndexOf(':');
      if (colon <= 0 || !isRunnerRepository(ref.substring(0, colon))) {
        continue;
      }
      if (ref.endsWith(":<none>") || sameImage(id, own.get())) {
        // Untagged is dangling — the prune's, not this sweep's; the running image is never touched.
        continue;
      }
      if (remove(ref, own.get(), "a leftover runner image")) {
        removed++;
      }
    }
    if (removed > 0) {
      LOG.infof("ci-runner removed %d leftover runner image(s)", removed);
    }
    return removed;
  }

  /** {@code registry.x/qits/qits-ci-runner}, or the bare repository on Docker Hub's spelling. */
  static boolean isRunnerRepository(String repository) {
    return repository.equals(RunnerArgv.RUNNER_REPOSITORY)
        || repository.endsWith("/" + RunnerArgv.RUNNER_REPOSITORY);
  }

  private boolean remove(String ref, String own, String why) {
    try {
      Docker.Result inspected = docker.run(RunnerArgv.imageInspect(dockerBinary, ref));
      if (!inspected.ok()) {
        // Already gone — removed by hand, or by another runner on the node.
        return false;
      }
      String id = inspected.stdout().strip();
      if (id.isEmpty() || sameImage(id, own)) {
        return false;
      }
      Docker.Result users = docker.run(RunnerArgv.psAncestor(dockerBinary, id));
      if (!users.ok()) {
        LOG.warnf(
            "ci-runner left %s: could not ask which containers use it: %s", ref, users.detail());
        return false;
      }
      if (!users.stdout().isBlank()) {
        LOG.infof("ci-runner left %s: a container still uses it", ref);
        return false;
      }
      Docker.Result gone = docker.run(RunnerArgv.imageRm(dockerBinary, ref));
      if (!gone.ok()) {
        LOG.warnf("ci-runner could not remove %s (%s): %s", ref, why, gone.detail());
        return false;
      }
      LOG.infof("ci-runner removed %s (%s)", ref, why);
      return true;
    } catch (IllegalArgumentException notAnImage) {
      LOG.warnf("ci-runner ignored an image reference: %s", notAnImage.getMessage());
      return false;
    }
  }

  /** The full id of the image this process's container runs; empty when it cannot be told. */
  Optional<String> ownImageId() {
    if (self.isEmpty()) {
      return Optional.empty();
    }
    Docker.Result answered = docker.run(RunnerArgv.containerImage(dockerBinary, self.get()));
    String id = answered.ok() ? answered.stdout().strip() : "";
    if (id.isEmpty()) {
      LOG.warnf(
          "ci-runner cannot tell which image it runs (%s); it removes no runner image",
          answered.ok() ? "docker answered nothing" : answered.detail());
      return Optional.empty();
    }
    return Optional.of(id);
  }

  /** Two image ids, either possibly without its {@code sha256:} prefix or truncated. */
  static boolean sameImage(String a, String b) {
    String x = a.startsWith("sha256:") ? a.substring(7) : a;
    String y = b.startsWith("sha256:") ? b.substring(7) : b;
    if (x.isEmpty() || y.isEmpty()) {
      return false;
    }
    return x.startsWith(y) || y.startsWith(x);
  }
}

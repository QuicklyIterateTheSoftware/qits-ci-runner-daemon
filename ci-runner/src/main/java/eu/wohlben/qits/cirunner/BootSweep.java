package eu.wohlben.qits.cirunner;

import org.jboss.logging.Logger;

/**
 * Remove every container this runner started and nobody reaped: {@code docker ps -aq --filter
 * label=qits.ci.runner=<id>}, then {@code docker rm -f} each.
 *
 * <p><b>Its own label, and only its own.</b> A runner shares its host with whatever else the person
 * runs there — other runners, their own containers, on the platform host the platform itself — and
 * a sweep that reached past its label would be the host-wide sweep qits-containers exists to have
 * removed. The builder container is deliberately under a different label and survives this.
 *
 * <p><b>It runs at every session start, not only at boot.</b> A runner that lost its socket is a
 * runner qits-ci has recorded as gone: every run it held is failed with the runner named, and the
 * containers of those runs belong to nothing. So the state a new session starts from is always "no
 * containers, no held runs", whether the process just started or the network blinked. A step whose
 * daemon was still talking to qits-ci across the blink is sacrificed, and that is the trade: a run is
 * recorded, retryable and never wedged, where a half-adopted one would be none of those.
 */
public final class BootSweep {

  private static final Logger LOG = Logger.getLogger(BootSweep.class);

  private final Docker docker;
  private final String dockerBinary;
  private final String runnerId;

  public BootSweep(Docker docker, String dockerBinary, String runnerId) {
    this.docker = docker;
    this.dockerBinary = dockerBinary;
    this.runnerId = runnerId;
  }

  /** One pass. Returns how many containers it removed; failures are logged, never thrown. */
  public int sweep() {
    Docker.Result listed = docker.run(RunnerArgv.psOwn(dockerBinary, runnerId));
    if (!listed.ok()) {
      // docker not answering is a restarted host's ordinary state; the next session sweeps again.
      LOG.warnf("ci-runner could not list its own containers: %s", listed.detail());
      return 0;
    }
    int removed = 0;
    for (String id : listed.stdout().split("\\s+")) {
      if (id.isBlank()) {
        continue;
      }
      Docker.Result gone;
      try {
        gone = docker.run(RunnerArgv.rm(dockerBinary, id.trim()));
      } catch (IllegalArgumentException notAnId) {
        LOG.warnf("ci-runner ignored a line docker ps answered: %s", notAnId.getMessage());
        continue;
      }
      if (gone.ok()) {
        removed++;
      } else {
        LOG.warnf("ci-runner could not remove its container %s: %s", id.trim(), gone.detail());
      }
    }
    if (removed > 0) {
      LOG.infof("ci-runner swept %d leftover container(s)", removed);
    }
    return removed;
  }
}

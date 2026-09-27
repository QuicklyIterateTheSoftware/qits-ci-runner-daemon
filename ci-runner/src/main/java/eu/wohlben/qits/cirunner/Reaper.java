package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.Reap;
import eu.wohlben.qits.cirunner.protocol.Reaped;
import org.jboss.logging.Logger;

/**
 * {@code Reap} and {@code Cancel}: remove containers the host is done with.
 *
 * <p>A {@code Reap} is answered with {@link Reaped} <b>whatever docker said</b>. The host is waiting
 * on it to move the run along, and there is nothing a failed removal would let it do differently: a
 * container that was already gone is reaped as far as anyone can tell, and one docker refused to
 * remove carries this runner's label, so the next session's {@link BootSweep} takes it.
 */
public final class Reaper {

  private static final Logger LOG = Logger.getLogger(Reaper.class);

  private final Docker docker;
  private final String dockerBinary;
  private final String runnerId;

  public Reaper(Docker docker, String dockerBinary, String runnerId) {
    this.docker = docker;
    this.dockerBinary = dockerBinary;
    this.runnerId = runnerId;
  }

  public Reaped reap(Reap reap) {
    try {
      Docker.Result gone = docker.run(RunnerArgv.rm(dockerBinary, reap.containerName()));
      if (!gone.ok()) {
        LOG.warnf(
            "ci-runner could not remove %s (run %s step %d): %s",
            reap.containerName(), reap.runId(), reap.stepIndex(), gone.detail());
      }
    } catch (IllegalArgumentException refused) {
      // A name outside the charset was never one of ours: nothing to remove, and the host still
      // gets its answer.
      LOG.warnf("ci-runner refused to reap: %s", refused.getMessage());
    }
    return new Reaped(reap.runId(), reap.stepIndex());
  }

  /**
   * Remove every container of one run, found by this runner's label and the run's — both, so a
   * cancel can never reach a container another runner on the same host started.
   */
  public int cancel(String runId) {
    Docker.Result listed;
    try {
      listed = docker.run(RunnerArgv.psRun(dockerBinary, runnerId, runId));
    } catch (IllegalArgumentException refused) {
      LOG.warnf("ci-runner refused to cancel: %s", refused.getMessage());
      return 0;
    }
    if (!listed.ok()) {
      LOG.warnf("ci-runner could not list run %s's containers: %s", runId, listed.detail());
      return 0;
    }
    int removed = 0;
    for (String id : listed.stdout().split("\\s+")) {
      if (!id.isBlank() && docker.run(RunnerArgv.rm(dockerBinary, id.trim())).ok()) {
        removed++;
      }
    }
    LOG.infof("ci-runner cancelled run %s (%d container(s) removed)", runId, removed);
    return removed;
  }
}

package eu.wohlben.qits.cirunner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * <p><b>It runs at every session start, not only at boot, and a reconnect spares the runs it
 * carries.</b> At boot nothing is held, so everything under the label is a leftover. A runner that
 * only lost its socket still holds the runs it took on the dropped connection, and qits-ci waits a
 * short grace for it to come back and claim them ({@code Hello.heldRuns}) — so their containers,
 * found by the run label beside the runner's, are kept, and a step whose daemon was still talking to
 * qits-ci across the blink carries on. Whether the host really kept a run is its {@code Ack}'s to
 * say; one it did not keep is cancelled then ({@link Reaper#cancel}), so nothing carried outlives
 * the host's answer. A carried run whose containers cannot be listed keeps the whole pass from
 * removing anything: a leftover waits for the next sweep, where a wrong guess would kill a live step.
 *
 * <p>Each container's output is written to the runner's log before it goes ({@link LogTail}): the
 * run it belonged to was failed without it, and the removal destroys the only copy.
 */
public final class BootSweep {

  private static final Logger LOG = Logger.getLogger(BootSweep.class);

  private final Docker docker;
  private final String dockerBinary;
  private final String runnerId;
  private final LogTail logTail;

  public BootSweep(Docker docker, String dockerBinary, String runnerId) {
    this.docker = docker;
    this.dockerBinary = dockerBinary;
    this.runnerId = runnerId;
    this.logTail = new LogTail(docker, dockerBinary);
  }

  /** One pass over everything under the label — a boot's. */
  public int sweep() {
    return sweep(List.of());
  }

  /**
   * One pass, sparing the containers of {@code carried} runs. Returns how many containers it
   * removed; failures are logged, never thrown.
   */
  public int sweep(Collection<String> carried) {
    Set<String> kept = new HashSet<>();
    for (String runId : carried) {
      Docker.Result run;
      try {
        run = docker.run(RunnerArgv.psRun(dockerBinary, runnerId, runId));
      } catch (IllegalArgumentException notAnId) {
        LOG.warnf("ci-runner ignored a carried run it cannot name: %s", notAnId.getMessage());
        continue;
      }
      if (!run.ok()) {
        LOG.warnf(
            "ci-runner could not list carried run %s's containers (%s); sweeping nothing this time",
            runId, run.detail());
        return 0;
      }
      kept.addAll(ids(run.stdout()));
    }
    Docker.Result listed = docker.run(RunnerArgv.psOwn(dockerBinary, runnerId));
    if (!listed.ok()) {
      // docker not answering is a restarted host's ordinary state; the next session sweeps again.
      LOG.warnf("ci-runner could not list its own containers: %s", listed.detail());
      return 0;
    }
    int removed = 0;
    for (String id : ids(listed.stdout())) {
      if (kept.contains(id)) {
        continue;
      }
      Docker.Result gone;
      try {
        Reaper.logLastWords(logTail, id.trim(), "a leftover of an earlier session");
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
    if (!kept.isEmpty()) {
      LOG.infof(
          "ci-runner kept %d container(s) of %d carried run(s)", kept.size(), carried.size());
    }
    return removed;
  }

  private static List<String> ids(String stdout) {
    List<String> ids = new ArrayList<>();
    for (String id : stdout.split("\\s+")) {
      if (!id.isBlank()) {
        ids.add(id.trim());
      }
    }
    return ids;
  }
}

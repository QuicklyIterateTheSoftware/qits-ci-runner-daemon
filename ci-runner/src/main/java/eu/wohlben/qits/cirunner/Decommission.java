package eu.wohlben.qits.cirunner;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * A deleted runner removing itself from its host: its container, every other container of its id,
 * and its state volume. The difference from a {@link Rollover} retirement is that nothing takes over
 * — there is no successor to remove the exited predecessor, and the state volume holds a client the
 * platform has already revoked, so it is dead weight rather than something to hand on.
 *
 * <pre>
 *   the runner process ({@link #leave}):
 *     client.json deleted          → a restart of anything under this volume cannot dial as it
 *     docker update --restart=no   → the exit that follows is its last — Rollover's step out of the way
 *     docker run -d the helper     → the runner's own image, in helper mode, with the docker socket
 *     exit 0
 *
 *   the helper ({@link #finish}):
 *     every container labelled qits.ci.runner.process=&lt;id&gt; → wait for it to exit, docker rm -f
 *     every container labelled qits.ci.runner=&lt;id&gt;         → docker rm -f (the step containers)
 *     docker volume rm qits-ci-runner-state-&lt;id8&gt;
 *     docker rm -f itself
 * </pre>
 *
 * <p><b>Why a helper at all.</b> A container can take its own restart policy away, and could even
 * {@code docker rm -f} itself, but it cannot remove the volume it has mounted: docker refuses to
 * remove a volume a container still uses, and the container is the one thing that cannot outlive
 * its own removal. So the last steps run from a second container — the same image, already on the
 * host, so there is nothing to pull — which waits for the runner the way a successor waits for its
 * predecessor ({@link Rollover#awaitExit}) and then removes it. Every container of the runner's id
 * goes, not only this process's: a runner deleted half way through a rollover has two, and both are
 * equally dead.
 *
 * <p><b>What survives a failure.</b> The restart policy goes before anything else can fail, so the
 * worst case is an exited container and a volume a person removes by hand — never a runner that
 * restarts and redials forever, which was the defect. A helper that fails stays, exited, with its
 * log; one that succeeds removes itself last.
 */
public final class Decommission {

  private static final Logger LOG = Logger.getLogger(Decommission.class);

  /** How long the helper waits for a runner container to exit, and how often it looks. */
  public record Settings(long exitWaitMillis, long pollMillis, int volumeAttempts) {

    public static Settings defaults() {
      return new Settings(60_000, 1_000, 10);
    }
  }

  private final Docker docker;
  private final String dockerBinary;
  private final String runnerId;
  private final Optional<String> self;
  private final Path stateDir;
  private final Settings settings;

  public Decommission(
      Docker docker,
      String dockerBinary,
      String runnerId,
      Optional<String> self,
      Path stateDir,
      Settings settings) {
    this.docker = docker;
    this.dockerBinary = dockerBinary;
    this.runnerId = runnerId;
    this.self = self;
    this.stateDir = stateDir;
    this.settings = settings;
  }

  /**
   * The runner process's half: forget the client, stay down, start the helper. Never throws; every
   * failure is logged and the process exits regardless — see the class javadoc for what is left.
   */
  public void leave() {
    try {
      Files.deleteIfExists(stateDir.resolve(Registration.CLIENT_FILE));
    } catch (IOException | RuntimeException e) {
      LOG.warnf("ci-runner could not delete its revoked client: %s", e.getMessage());
    }
    if (self.isEmpty()) {
      LOG.warn(
          "ci-runner is not running in a docker container, so there is no container to remove;"
              + " stop whatever supervises this process");
      return;
    }
    String selfId = self.get();
    Docker.Result stayDown = docker.run(RunnerArgv.noRestart(dockerBinary, selfId));
    if (!stayDown.ok()) {
      LOG.warnf("ci-runner could not take its own restart policy away: %s", stayDown.detail());
    }
    Docker.Result inspected =
        docker.run(RunnerArgv.inspectSelf(dockerBinary, selfId), Docker.MAX_DOCUMENT);
    if (!inspected.ok()) {
      LOG.warnf(
          "ci-runner could not inspect its own container, so it cannot start the helper that"
              + " removes it: %s; remove %s by hand",
          inspected.detail(), selfId);
      return;
    }
    String image;
    String volume;
    try {
      image = SelfSpec.imageIdOf(inspected.stdout());
      volume = stateVolume(inspected.stdout(), stateDir).orElse(null);
    } catch (RuntimeException e) {
      LOG.warnf("ci-runner could not read its own container: %s; remove it by hand", e.getMessage());
      return;
    }
    if (volume == null) {
      LOG.infof("ci-runner's state %s is no named volume; it is left where it is", stateDir);
    }
    Docker.Result started;
    try {
      started =
          docker.run(RunnerArgv.runDecommissioner(dockerBinary, runnerId, image, volume));
    } catch (IllegalArgumentException refused) {
      LOG.warnf("ci-runner cannot start its decommission helper: %s", refused.getMessage());
      return;
    }
    if (started.ok()) {
      LOG.infof(
          "ci-runner started %s to remove its container%s",
          RunnerArgv.decommissionerName(runnerId), volume == null ? "" : " and the volume " + volume);
    } else {
      LOG.warnf(
          "ci-runner could not start its decommission helper (%s); its container stays, exited, and"
              + " never restarts",
          started.detail());
    }
  }

  /**
   * The helper's job, run by the binary in helper mode ({@link RunnerArgv#DECOMMISSION_ENV}), before
   * and without Quarkus — so it reports through {@code say} rather than a logger. {@code self} is the
   * helper's own container, removed last. Answers the process exit code: 0 when everything went, 1
   * when anything is left (and then the helper is left too, with this output in its log).
   */
  public static int finish(
      Docker docker,
      String dockerBinary,
      String runnerId,
      String volume,
      Optional<String> self,
      Settings settings,
      Consumer<String> say) {
    boolean clean = true;
    Docker.Result processes = docker.run(RunnerArgv.psProcess(dockerBinary, runnerId));
    if (!processes.ok()) {
      say.accept("could not list runner " + runnerId + "'s containers: " + processes.detail());
      clean = false;
    } else {
      for (String line : processes.stdout().split("\\R")) {
        String id = line.strip().split("\\|", -1)[0].strip();
        if (id.isEmpty()) {
          continue;
        }
        String state =
            Rollover.awaitExit(
                docker, dockerBinary, id, settings.exitWaitMillis(), settings.pollMillis());
        clean &= remove(docker, dockerBinary, id, "runner container (" + state + ")", say);
      }
    }
    Docker.Result steps = docker.run(RunnerArgv.psOwn(dockerBinary, runnerId));
    if (steps.ok()) {
      for (String id : steps.stdout().split("\\s+")) {
        if (!id.isBlank()) {
          clean &= remove(docker, dockerBinary, id.strip(), "step container", say);
        }
      }
    } else {
      say.accept("could not list runner " + runnerId + "'s step containers: " + steps.detail());
      clean = false;
    }
    if (volume != null && !volume.isBlank()) {
      clean &= removeVolume(docker, dockerBinary, volume, settings, say);
    }
    if (!clean) {
      say.accept("decommission of runner " + runnerId + " is incomplete; this helper stays");
      return 1;
    }
    say.accept("runner " + runnerId + " is decommissioned");
    // Last, and only on success: this is the process being removed. Whatever docker answers, the job
    // is done — an exited helper is the one thing it leaves if this fails.
    self.ifPresent(id -> docker.run(RunnerArgv.rm(dockerBinary, id)));
    return 0;
  }

  private static boolean remove(
      Docker docker, String dockerBinary, String id, String what, Consumer<String> say) {
    try {
      Docker.Result gone = docker.run(RunnerArgv.rm(dockerBinary, id));
      if (gone.ok()) {
        say.accept("removed " + what + " " + id);
        return true;
      }
      say.accept("could not remove " + what + " " + id + ": " + gone.detail());
    } catch (IllegalArgumentException notAnId) {
      say.accept("ignored a line docker ps answered: " + notAnId.getMessage());
      return true;
    }
    return false;
  }

  /**
   * A volume is released a moment after the container using it is removed, so a refusal is retried
   * a few times before it counts. An absent volume is as good as a removed one.
   */
  private static boolean removeVolume(
      Docker docker, String dockerBinary, String volume, Settings settings, Consumer<String> say) {
    Docker.Result gone = null;
    for (int attempt = 0; attempt < Math.max(1, settings.volumeAttempts()); attempt++) {
      gone = docker.run(RunnerArgv.volumeRm(dockerBinary, volume));
      if (gone.ok() || gone.detail().toLowerCase().contains("no such volume")) {
        say.accept("removed the state volume " + volume);
        return true;
      }
      try {
        Thread.sleep(settings.pollMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    say.accept("could not remove the state volume " + volume + ": " + gone.detail());
    return false;
  }

  /**
   * The named volume mounted at {@code stateDir} in the container {@code docker inspect} answered
   * for — the install contract's {@code qits-ci-runner-state-<id8>}, read rather than composed, so a
   * runner an operator started with a volume of another name removes that one. The top-level {@code
   * Mounts} first, which lists every mount however it was declared; {@code HostConfig.Binds} and
   * {@code HostConfig.Mounts} after, for a document that lacks it. A bind of a host path is not a
   * volume and answers empty: a host directory is the operator's, not the runner's to delete.
   */
  static Optional<String> stateVolume(String containerJson, Path stateDir) {
    JsonArray array = new JsonArray(containerJson == null ? "[]" : containerJson.strip());
    if (array.isEmpty() || !(array.getValue(0) instanceof JsonObject container)) {
      return Optional.empty();
    }
    String target = stateDir.toString();
    JsonArray mounts = container.getJsonArray("Mounts");
    if (mounts != null) {
      for (Object item : mounts) {
        if (item instanceof JsonObject mount && target.equals(mount.getString("Destination"))) {
          return "volume".equals(mount.getString("Type"))
              ? Optional.ofNullable(mount.getString("Name")).filter(n -> !n.isBlank())
              : Optional.empty();
        }
      }
    }
    JsonObject hostConfig = container.getJsonObject("HostConfig");
    if (hostConfig == null) {
      return Optional.empty();
    }
    JsonArray binds = hostConfig.getJsonArray("Binds");
    if (binds != null) {
      for (Object item : binds) {
        if (item instanceof String bind) {
          String[] parts = bind.split(":");
          if (parts.length >= 2 && parts[1].equals(target)) {
            return parts[0].startsWith("/") ? Optional.empty() : Optional.of(parts[0]);
          }
        }
      }
    }
    JsonArray declared = hostConfig.getJsonArray("Mounts");
    if (declared != null) {
      for (Object item : declared) {
        if (item instanceof JsonObject mount && target.equals(mount.getString("Target"))) {
          return "volume".equals(mount.getString("Type"))
              ? Optional.ofNullable(mount.getString("Source")).filter(n -> !n.isBlank())
              : Optional.empty();
        }
      }
    }
    return Optional.empty();
  }
}

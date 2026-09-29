package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import eu.wohlben.qits.cirunner.protocol.Launch;
import eu.wohlben.qits.cirunner.protocol.LaunchFailed;
import eu.wohlben.qits.cirunner.protocol.Launched;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import org.jboss.logging.Logger;

/**
 * {@code Launch} → {@code docker run -d} → {@link Launched} or {@link LaunchFailed}. The step's
 * daemon dials qits-ci itself from here on; the runner's part in the step is over once this answers.
 *
 * <p>The order is the answer's order of usefulness: a spec this runner refuses fails before docker
 * is asked anything, a builder that cannot come up fails before the image is fetched, and an image
 * the host does not have is pulled as its own call — so "the registry has no such image" reaches the
 * run as docker's own words rather than as a {@code run} that failed somewhere inside.
 *
 * <p><b>{@code BUILDKIT_HOST} is filled only when the spec left the key absent.</b> An empty value is
 * qits-ci's "buildkit is switched off", the platform's empty-never-absent off value, and a step told
 * that must fail loudly at its first {@code buildctl} rather than build through somebody's socket.
 */
public final class Launcher {

  private static final Logger LOG = Logger.getLogger(Launcher.class);

  /**
   * The docker {@code config.json} document qits-ci sends a step as its registry login — the run's
   * credential, one entry per registry host the step reaches. The runner reads it for the step
   * image's pull; the step's own bootstrap reads it for its builds.
   */
  static final String REGISTRY_AUTH_CONFIG = "QITS_CI_REGISTRY_AUTH_CONFIG";

  /** How much of docker's diagnosis rides a {@link LaunchFailed}. */
  static final int MAX_DETAIL = 2000;

  private final Docker docker;
  private final String dockerBinary;
  private final String runnerId;

  /**
   * The env-configured builder {@link Main} built from {@code RunnerEnv} — kept aside, never
   * mutated, so every {@link #onAck} merges qits-ci's latest map against the same baseline rather
   * than layering it onto whatever a previous {@code Ack} produced. See {@link
   * BuildPlane#withRegistryMirrors}.
   */
  private final BuildPlane envBuildPlane;

  /** What {@link #launch} actually uses — swapped by {@link #onAck}, read fresh on every launch. */
  private final AtomicReference<BuildPlane> buildPlane;

  /** The last map actually applied, so a repeat {@code Ack} (a slots-only change, say) logs nothing. */
  private volatile Map<String, String> appliedRegistryMirrors = Map.of();

  /** Every step image a launch used, for {@link Housekeeping}'s sweep — see {@link StepImages}. */
  private final StepImages stepImages;

  /** A launcher whose image record lives in memory only. */
  public Launcher(Docker docker, String dockerBinary, String runnerId, BuildPlane buildPlane) {
    this(docker, dockerBinary, runnerId, buildPlane, StepImages.inMemory());
  }

  public Launcher(
      Docker docker,
      String dockerBinary,
      String runnerId,
      BuildPlane buildPlane,
      StepImages stepImages) {
    this.docker = docker;
    this.dockerBinary = dockerBinary;
    this.runnerId = runnerId;
    this.envBuildPlane = buildPlane;
    this.buildPlane = new AtomicReference<>(buildPlane);
    this.stepImages = stepImages;
  }

  /**
   * Replace the node's builder when it runs a configuration other than the current one — {@link
   * BuildPlane#refreshIfStale}. For {@link Housekeeping}, which calls it only while no run is held.
   */
  public void refreshBuilderIfStale() {
    buildPlane.get().refreshIfStale();
  }

  /**
   * qits-ci's {@code Ack} carried a registry-mirror map for the builder ({@link
   * eu.wohlben.qits.cirunner.protocol.Ack#registryMirrors()}): merge it over the env-configured
   * mirrors and swap the {@link BuildPlane} the next {@link #launch} will {@link BuildPlane#ensure
   * ensure}. {@code null} or empty — no host has sent one yet, or this {@code Ack} is a plain
   * slots update — leaves the current builder exactly as it was; qits-ci resends {@code Ack} for
   * reasons that have nothing to do with the mirror map; nothing here reserves this method a
   * "first call only" the way {@code Rollover.removePredecessors} is.
   */
  public void onAck(Map<String, String> ackMirrors) {
    Map<String, String> normalized = ackMirrors == null ? Map.of() : ackMirrors;
    if (normalized.isEmpty() || normalized.equals(appliedRegistryMirrors)) {
      return;
    }
    appliedRegistryMirrors = normalized;
    buildPlane.set(envBuildPlane.withRegistryMirrors(normalized));
    LOG.infof("ci-runner builder mirrors from qits-ci: %d registries", normalized.size());
  }

  public CiRunnerMessage launch(Launch launch) {
    WorkloadSpec spec = launch.workloadSpec();
    if (spec == null) {
      return failed(launch, "the Launch carried no workload spec");
    }
    boolean builds = spec.buildPlane() || spec.hostDockerSocket();
    List<String> networks = new ArrayList<>();
    if (spec.network() != null) {
      networks.add(spec.network());
    }
    Map<String, String> env = new LinkedHashMap<>(spec.env());
    if (builds) {
      if (!networks.contains(BuildPlane.NETWORK)) {
        networks.add(BuildPlane.NETWORK);
      }
      env.putIfAbsent(BuildPlane.BUILDKIT_HOST, BuildPlane.ADDRESS);
    }
    List<String> run;
    try {
      run = RunnerArgv.run(dockerBinary, runnerId, launch.runId(), spec, networks, env);
    } catch (IllegalArgumentException refused) {
      // Before any docker call: a spec the belts refuse is refused whole.
      return failed(launch, "refused by the runner: " + refused.getMessage());
    }
    if (builds) {
      Optional<String> builderFailure = buildPlane.get().ensure(spec.network());
      if (builderFailure.isPresent()) {
        return failed(launch, "the build plane is not available: " + builderFailure.get());
      }
    }
    // From the inspect to the run, the image sweep keeps its hands off — see StepImages.
    Docker.Result started;
    Lock launching = stepImages.launching();
    launching.lock();
    try {
      Optional<String> pullFailure = ensureImage(spec);
      if (pullFailure.isPresent()) {
        return failed(launch, pullFailure.get());
      }
      stepImages.used(spec.image());
      started = docker.run(run);
    } finally {
      launching.unlock();
    }
    if (!started.ok()) {
      return failed(launch, "docker run failed: " + started.detail());
    }
    String[] lines = started.stdout().strip().split("\\R");
    String containerId = lines[lines.length - 1].strip();
    LOG.infof(
        "ci-runner launched run %s step %d as %s", launch.runId(), launch.stepIndex(), spec.name());
    return new Launched(launch.runId(), launch.stepIndex(), containerId);
  }

  /**
   * Have the step's image on this host, pulling it when it is not — under the launch's own registry
   * login when the spec carries one ({@link #REGISTRY_AUTH_CONFIG}), and under the host's docker
   * config otherwise, which is today's behaviour exactly.
   *
   * <p><b>Why the spec's login, and why only for these two calls.</b> An EDGE step's image comes from
   * the platform registry's public vhost, which answers an anonymous {@code /v2} with 401, and the
   * host's docker config holds no login for it — nor should it: a login written there would outlive
   * the run and serve every other docker user on the host. qits-ci already sends the run's
   * credential as a docker {@code config.json} document for the step's own use, so the pull borrows
   * that document for exactly its own two invocations, through {@code docker --config}, from a
   * directory only this process can read (0700, the file 0600) that is deleted in the {@code
   * finally} whatever happened. The {@code run} is not given it: the image is local by then, and the
   * container gets the document through its environment as it always did.
   *
   * <p><b>The document is never logged and never echoed.</b> A failure names the image and docker's
   * words; a staging failure names only what went wrong with the directory. Should docker's stderr
   * ever repeat a credential, {@link #redact} takes it out before the detail leaves this host.
   *
   * @return empty when the image is here, else the failure's detail
   */
  private Optional<String> ensureImage(WorkloadSpec spec) {
    String document = spec.env().get(REGISTRY_AUTH_CONFIG);
    Path configDir = null;
    try {
      if (document != null && !document.isBlank()) {
        try {
          configDir = stageConfig(document);
        } catch (IOException | UnsupportedOperationException staging) {
          return Optional.of(
              "could not stage the registry login for docker pull "
                  + spec.image()
                  + ": "
                  + staging.getClass().getSimpleName());
        }
      }
      if (docker.run(RunnerArgv.imageInspect(dockerBinary, configDir, spec.image())).ok()) {
        return Optional.empty();
      }
      Docker.Result pulled = docker.run(RunnerArgv.pull(dockerBinary, configDir, spec.image()));
      if (!pulled.ok()) {
        return Optional.of(
            "docker pull " + spec.image() + " failed: " + redact(pulled.detail(), document));
      }
      return Optional.empty();
    } finally {
      removeConfig(configDir);
    }
  }

  /** A fresh 0700 directory holding {@code config.json} at 0600, created with those modes. */
  static Path stageConfig(String document) throws IOException {
    Path dir =
        Files.createTempDirectory(
            "qits-ci-runner-pull-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Path file =
        Files.createFile(
            dir.resolve("config.json"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(file, document, StandardCharsets.UTF_8);
    return dir;
  }

  static void removeConfig(Path dir) {
    if (dir == null) {
      return;
    }
    try {
      Files.deleteIfExists(dir.resolve("config.json"));
      Files.deleteIfExists(dir);
    } catch (IOException e) {
      // The path only, never the content: a stranded 0600 file in a 0700 directory is the worst case.
      LOG.warnf("ci-runner could not remove its pull login at %s: %s", dir, e.getMessage());
    }
  }

  /**
   * {@code detail} with the document and every {@code auth} value inside it replaced — docker does
   * not repeat a login on a failed pull, and this is what keeps that a fact about this host rather
   * than a promise about every docker version it will ever run.
   */
  static String redact(String detail, String document) {
    if (detail == null || document == null || document.isBlank()) {
      return detail;
    }
    String redacted = detail.replace(document, "[redacted]");
    try {
      JsonObject auths = new JsonObject(document).getJsonObject("auths");
      if (auths != null) {
        for (String host : auths.fieldNames()) {
          Object entry = auths.getValue(host);
          if (entry instanceof JsonObject login) {
            String auth = login.getString("auth");
            if (auth != null && !auth.isBlank()) {
              redacted = redacted.replace(auth, "[redacted]");
            }
          }
        }
      }
    } catch (RuntimeException notJson) {
      // Nothing more to recognise; the whole document is already out.
    }
    return redacted;
  }

  private static LaunchFailed failed(Launch launch, String detail) {
    String bounded =
        detail.length() <= MAX_DETAIL ? detail : "…" + detail.substring(detail.length() - MAX_DETAIL);
    LOG.warnf(
        "ci-runner could not launch run %s step %d: %s", launch.runId(), launch.stepIndex(), bounded);
    return new LaunchFailed(launch.runId(), launch.stepIndex(), bounded);
  }
}

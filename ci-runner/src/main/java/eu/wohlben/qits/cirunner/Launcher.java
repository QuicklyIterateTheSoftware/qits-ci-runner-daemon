package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import eu.wohlben.qits.cirunner.protocol.Launch;
import eu.wohlben.qits.cirunner.protocol.LaunchFailed;
import eu.wohlben.qits.cirunner.protocol.Launched;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  /** How much of docker's diagnosis rides a {@link LaunchFailed}. */
  static final int MAX_DETAIL = 2000;

  private final Docker docker;
  private final String dockerBinary;
  private final String runnerId;
  private final BuildPlane buildPlane;

  public Launcher(Docker docker, String dockerBinary, String runnerId, BuildPlane buildPlane) {
    this.docker = docker;
    this.dockerBinary = dockerBinary;
    this.runnerId = runnerId;
    this.buildPlane = buildPlane;
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
      Optional<String> builderFailure = buildPlane.ensure(spec.network());
      if (builderFailure.isPresent()) {
        return failed(launch, "the build plane is not available: " + builderFailure.get());
      }
    }
    if (!docker.run(RunnerArgv.imageInspect(dockerBinary, spec.image())).ok()) {
      Docker.Result pulled = docker.run(RunnerArgv.pull(dockerBinary, spec.image()));
      if (!pulled.ok()) {
        return failed(launch, "docker pull " + spec.image() + " failed: " + pulled.detail());
      }
    }
    Docker.Result started = docker.run(run);
    if (!started.ok()) {
      return failed(launch, "docker run failed: " + started.detail());
    }
    String[] lines = started.stdout().strip().split("\\R");
    String containerId = lines[lines.length - 1].strip();
    LOG.infof(
        "ci-runner launched run %s step %d as %s", launch.runId(), launch.stepIndex(), spec.name());
    return new Launched(launch.runId(), launch.stepIndex(), containerId);
  }

  private static LaunchFailed failed(Launch launch, String detail) {
    String bounded =
        detail.length() <= MAX_DETAIL ? detail : "…" + detail.substring(detail.length() - MAX_DETAIL);
    LOG.warnf(
        "ci-runner could not launch run %s step %d: %s", launch.runId(), launch.stepIndex(), bounded);
    return new LaunchFailed(launch.runId(), launch.stepIndex(), bounded);
  }
}

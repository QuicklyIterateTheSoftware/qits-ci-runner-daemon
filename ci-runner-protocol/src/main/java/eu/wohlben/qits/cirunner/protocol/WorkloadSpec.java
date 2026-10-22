package eu.wohlben.qits.cirunner.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One step container, as qits-ci asks a runner for it — the subset of qits-containers' {@code
 * ContainerSpec} that qits-ci's {@code CiDaemonLauncher.buildWorkloadSpec} actually fills, restated
 * here so the runner depends on this jar and nothing of qits-containers.
 *
 * <p><b>The shape is the security boundary</b>, as it is for {@code ContainerSpec}: there is no
 * free-form argv, no volume and no host path. The one bind a runner will ever make is {@link
 * #hostDockerSocket}, a boolean precisely so that no sender can choose what gets mounted. Fields
 * {@code ContainerSpec} has and qits-ci never sets for a step — aliases, volume and shared mounts,
 * pull policy — are absent rather than carried empty: a field on the wire is a capability the
 * runner has to implement and defend, and nothing asks for these. {@link #init} is the exception,
 * carried since qits-1509 because qits-ci needs a reaper (see below).
 *
 * <p><b>The sandbox is flattened</b> ({@code capDropAll} … {@code oomScoreAdj} rather than a nested
 * {@code security} object) because this is a wire record with one consumer, and a nested object is
 * one more thing both codecs spell. A null limit renders no flag, so "unset" stays different from
 * "off", exactly as {@code SecurityPosture} has it.
 *
 * <p><b>{@link #buildPlane} is the one field {@code ContainerSpec} has no counterpart for</b>, and
 * it is here because nothing existing carries the fact. {@code hostDockerSocket} says {@code docker:
 * true}, but a {@code build: true} step needs a builder and holds no socket; qits-containers
 * sidesteps the question by handing {@code BUILDKIT_HOST} to every workload named {@code ci-step},
 * which is a name the runner never sees. Inferring it from an environment key the step script reads
 * ({@code QITS_BUILD_REGISTRY}) would make a variable meant for a script into a control signal. So
 * qits-ci says it: {@code buildPlane = docker || build}, and the runner ensures its buildkitd for
 * exactly those steps and fills {@code BUILDKIT_HOST} when the spec left the key absent — an empty
 * value is the platform's "switched off" and is never overwritten.
 *
 * @param image the reference to run.
 * @param entrypoint overrides the image's own, or empty to keep it; a longer list spends its tail as
 *     leading arguments after the image, the docker CLI's own convention.
 * @param args what follows the image.
 * @param env the container's environment. Carries the step's credentials: never logged.
 * @param labels the sender's bookkeeping ({@code qits.ci.run}). Keys in the runner's own {@code
 *     qits.ci.runner} namespace are refused by the runner.
 * @param network the network {@code docker run} attaches to, or null for docker's default.
 * @param extraHosts {@code name:target} entries — {@code host.docker.internal:host-gateway}.
 * @param user {@code docker run --user}, or null for the image's own default.
 * @param hostDockerSocket the one bind: {@code /var/run/docker.sock}, root-equivalent on the host.
 * @param name the container name — qits-ci's own, so {@link Reap} can address it.
 * @param buildPlane the step builds images and needs the runner's buildkitd — see above.
 * @param init start the container with docker's {@code --init}, so an init process is PID 1 and
 *     reaps the orphans repo-controlled code leaves (each would otherwise hold a pid against
 *     {@code --pids-limit}).
 */
public record WorkloadSpec(
    String image,
    List<String> entrypoint,
    List<String> args,
    Map<String, String> env,
    Map<String, String> labels,
    String network,
    List<String> extraHosts,
    String user,
    boolean hostDockerSocket,
    boolean capDropAll,
    boolean noNewPrivileges,
    String memory,
    String memorySwap,
    Long pidsLimit,
    String cpus,
    Integer oomScoreAdj,
    String name,
    boolean buildPlane,
    boolean init) {

  /**
   * Nulls normalize to empty collections and blank optional strings to null, so a sender that
   * omits a field and one that sends {@code []} or {@code ""} mean the same thing — and the codec's
   * round trip is an identity. Insertion order of the maps is kept; the runner sorts what it
   * renders.
   */
  public WorkloadSpec {
    entrypoint = entrypoint == null ? List.of() : List.copyOf(entrypoint);
    args = args == null ? List.of() : List.copyOf(args);
    env = ordered(env);
    labels = ordered(labels);
    network = blankToNull(network);
    extraHosts = extraHosts == null ? List.of() : List.copyOf(extraHosts);
    user = blankToNull(user);
    memory = blankToNull(memory);
    memorySwap = blankToNull(memorySwap);
    cpus = blankToNull(cpus);
    name = blankToNull(name);
  }

  /**
   * The signature before {@link #init} existed, for a sender compiled against the older jar —
   * qits-ci-service, until it bumps past qits-1509. Delegates with {@code init = false}, so a
   * caller that never heard of the flag starts a container exactly as it did before.
   */
  public WorkloadSpec(
      String image,
      List<String> entrypoint,
      List<String> args,
      Map<String, String> env,
      Map<String, String> labels,
      String network,
      List<String> extraHosts,
      String user,
      boolean hostDockerSocket,
      boolean capDropAll,
      boolean noNewPrivileges,
      String memory,
      String memorySwap,
      Long pidsLimit,
      String cpus,
      Integer oomScoreAdj,
      String name,
      boolean buildPlane) {
    this(
        image, entrypoint, args, env, labels, network, extraHosts, user, hostDockerSocket,
        capDropAll, noNewPrivileges, memory, memorySwap, pidsLimit, cpus, oomScoreAdj, name,
        buildPlane, false);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static Map<String, String> ordered(Map<String, String> values) {
    if (values == null || values.isEmpty()) {
      return Map.of();
    }
    Map<String, String> copy = new LinkedHashMap<>();
    values.forEach((k, v) -> copy.put(k, v == null ? "" : v));
    return Collections.unmodifiableMap(copy);
  }

  /** A spec with only an image and a name — the start of every test and nothing else. */
  public static WorkloadSpec of(String image, String name) {
    return new WorkloadSpec(
        image, null, null, null, null, null, null, null, false, false, false, null, null, null,
        null, null, name, false, false);
  }
}

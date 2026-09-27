package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Every docker command line this runner runs, as pure functions — qits-containers' {@code
 * DockerArgv} is the reference, and the same argument holds here: a spec goes in and a {@code
 * List<String>} comes out, so the argv can be asserted element for element in a docker-free suite.
 * <b>The argv is the sandbox</b>, and a flag lost in a refactor is invisible everywhere else.
 *
 * <p><b>The belts run here</b>, on every value that reaches an element, because the spec arrives
 * over a socket and this is the last place before a root-equivalent daemon. A value outside its
 * charset is an {@link IllegalArgumentException}, which the launcher turns into a {@code
 * LaunchFailed} naming it. Nothing is ever assembled into a shell line; every value is one element.
 *
 * <p><b>{@code --rm} appears nowhere</b>, for DockerArgv's reason: a self-removing container races
 * the diagnosis, and every teardown is an explicit {@code rm -f} the host asked for.
 */
public final class RunnerArgv {

  /** The host docker socket, on both sides of the bind — a constant, never a caller's path. */
  public static final String DOCKER_SOCKET = "/var/run/docker.sock";

  /**
   * The runner's own label: every container this runner starts carries {@code
   * qits.ci.runner=<runner id>}, and the boot sweep removes exactly those. It is also the namespace
   * a sender's labels may not write into — a spec label that forged it would make a container the
   * sweep reaps, or hide one from it.
   */
  public static final String RUNNER_LABEL = "qits.ci.runner";

  /** Which run a container belongs to, so {@code Cancel{runId}} can find all of them by filter. */
  public static final String RUN_LABEL = "qits.ci.runner.run";

  private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
  private static final Pattern IMAGE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._:/@+-]{0,511}");
  private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,255}");
  private static final Pattern LABEL_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}");
  private static final Pattern ADD_HOST = Pattern.compile("[A-Za-z0-9._-]{1,253}:[A-Za-z0-9.:_-]{1,253}");
  private static final Pattern USER = Pattern.compile("[A-Za-z0-9._-]{1,64}(:[A-Za-z0-9._-]{1,64})?");
  private static final Pattern SIZE = Pattern.compile("[0-9]{1,15}[bkmgBKMG]?");
  private static final Pattern CPUS = Pattern.compile("[0-9]{1,4}(\\.[0-9]{1,3})?");

  private RunnerArgv() {}

  /**
   * The whole {@code docker run} for one step. Detached, never self-removing, in a fixed order.
   *
   * <p>{@code networks} is what the launcher decided: the spec's own network when it names one, then
   * the runner's build network when the step needs the build plane. Empty renders no {@code
   * --network} at all — docker's default bridge — which is what "the spec names none" means. More
   * than one renders one flag each, which docker accepts from API 1.44 (Engine 25) on.
   *
   * <p>{@code env} is passed separately from the spec because the launcher may have filled {@code
   * BUILDKIT_HOST} into it; the spec itself is never mutated.
   */
  public static List<String> run(
      String dockerBinary,
      String runnerId,
      String runId,
      WorkloadSpec spec,
      List<String> networks,
      Map<String, String> env) {
    List<String> argv = new ArrayList<>();
    argv.add(dockerBinary);
    argv.add("run");
    argv.add("-d");
    argv.add("--name");
    argv.add(require(NAME, "container name", spec.name()));
    for (String network : networks) {
      argv.add("--network");
      argv.add(require(NAME, "network", network));
    }
    for (String addHost : spec.extraHosts()) {
      argv.add("--add-host=" + require(ADD_HOST, "extra host", addHost));
    }
    // Only when the spec named somebody: an unset user is the image's own default, and --cap-drop
    // below means the choice cannot be made from inside the container later.
    if (spec.user() != null) {
      argv.add("--user");
      argv.add(require(USER, "user", spec.user()));
    }
    // Sorted, because the argv is asserted literally. The runner's own two are added last and
    // cannot collide: a sender's key inside the namespace is refused first.
    Map<String, String> labels = new TreeMap<>();
    for (Map.Entry<String, String> label : spec.labels().entrySet()) {
      String key = require(LABEL_KEY, "label key", label.getKey());
      if (key.equals(RUNNER_LABEL) || key.startsWith(RUNNER_LABEL + ".")) {
        throw new IllegalArgumentException(
            "label '" + key + "' is inside the runner's own namespace " + RUNNER_LABEL);
      }
      labels.put(key, oneLine("label value", label.getValue()));
    }
    labels.put(RUNNER_LABEL, require(NAME, "runner id", runnerId));
    labels.put(RUN_LABEL, require(NAME, "run id", runId));
    for (Map.Entry<String, String> label : labels.entrySet()) {
      argv.add("--label");
      argv.add(label.getKey() + "=" + label.getValue());
    }
    // The sandbox, each flag only when asked for, so "unset" stays different from "off".
    if (spec.noNewPrivileges()) {
      argv.add("--security-opt=no-new-privileges");
    }
    if (spec.capDropAll()) {
      argv.add("--cap-drop=ALL");
    }
    if (spec.memory() != null) {
      argv.add("--memory");
      argv.add(require(SIZE, "memory", spec.memory()));
    }
    if (spec.memorySwap() != null) {
      argv.add("--memory-swap");
      argv.add(require(SIZE, "memory swap", spec.memorySwap()));
    }
    if (spec.pidsLimit() != null) {
      argv.add("--pids-limit");
      argv.add(String.valueOf(spec.pidsLimit()));
    }
    if (spec.cpus() != null) {
      argv.add("--cpus");
      argv.add(require(CPUS, "cpus", spec.cpus()));
    }
    // A host-survival hint rather than a limit: under memory pressure the kernel takes a step
    // before the runner or anything else the host is for. A reaped step retries; a host does not.
    if (spec.oomScoreAdj() != null) {
      argv.add("--oom-score-adj");
      argv.add(String.valueOf(spec.oomScoreAdj()));
    }
    // The one bind, root-equivalent on the host, and here only because the spec DECLARED it.
    if (spec.hostDockerSocket()) {
      argv.add("-v");
      argv.add(DOCKER_SOCKET + ":" + DOCKER_SOCKET);
    }
    for (Map.Entry<String, String> variable : new TreeMap<>(env).entrySet()) {
      argv.add("-e");
      argv.add(require(ENV_KEY, "environment key", variable.getKey()) + "=" + variable.getValue());
    }
    // --entrypoint takes one word; a longer list spends its tail after the image.
    List<String> entrypoint = spec.entrypoint();
    if (!entrypoint.isEmpty()) {
      argv.add("--entrypoint");
      argv.add(entrypoint.getFirst());
    }
    argv.add(require(IMAGE, "image", spec.image()));
    if (entrypoint.size() > 1) {
      argv.addAll(entrypoint.subList(1, entrypoint.size()));
    }
    argv.addAll(spec.args());
    return List.copyOf(argv);
  }

  /** Does the host have the image? A nonzero answer is "no", which is what triggers the pull. */
  public static List<String> imageInspect(String dockerBinary, String image) {
    return List.of(
        dockerBinary, "image", "inspect", "--format", "{{.Id}}", require(IMAGE, "image", image));
  }

  /** Fetch the image, so "the registry has no such image" is its own answer rather than a run's. */
  public static List<String> pull(String dockerBinary, String image) {
    return List.of(dockerBinary, "pull", require(IMAGE, "image", image));
  }

  /** Remove it, running or not. Every teardown ends here. */
  public static List<String> rm(String dockerBinary, String name) {
    return List.of(dockerBinary, "rm", "-f", require(NAME, "container name", name));
  }

  /** This runner's containers — the boot sweep's whole selection, its own label and nothing else. */
  public static List<String> psOwn(String dockerBinary, String runnerId) {
    return List.of(
        dockerBinary,
        "ps",
        "-aq",
        "--filter",
        "label=" + RUNNER_LABEL + "=" + require(NAME, "runner id", runnerId));
  }

  /** One run's containers on this runner — both labels, so a cancel cannot reach another runner's. */
  public static List<String> psRun(String dockerBinary, String runnerId, String runId) {
    return List.of(
        dockerBinary,
        "ps",
        "-aq",
        "--filter",
        "label=" + RUNNER_LABEL + "=" + require(NAME, "runner id", runnerId),
        "--filter",
        "label=" + RUN_LABEL + "=" + require(NAME, "run id", runId));
  }

  /** The image belt, for the one argv here that is not built from a spec. */
  public static String requireImage(String image) {
    return require(IMAGE, "image", image);
  }

  /** Belt-check a value against its charset; the message names what it was. */
  static String require(Pattern pattern, String what, String value) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid " + what + ": '" + value + "'");
    }
    return value;
  }

  private static String oneLine(String what, String value) {
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("invalid " + what + ": it spans lines");
    }
    return value;
  }
}

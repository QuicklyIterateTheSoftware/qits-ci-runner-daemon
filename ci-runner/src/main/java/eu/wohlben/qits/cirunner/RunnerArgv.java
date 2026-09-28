package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import java.nio.file.Path;
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

  /**
   * The runner's OWN container: {@code qits.ci.runner.process=<runner id>}, on the container the
   * install script starts and on every successor. Deliberately not {@link #RUNNER_LABEL}: the boot
   * sweep removes every container carrying that one, and a runner that matched its own sweep would
   * remove itself on every reconnect. A step cannot forge it — it is inside the namespace {@link
   * #run} refuses in a spec.
   */
  public static final String PROCESS_LABEL = "qits.ci.runner.process";

  /** Which runner version a runner container runs — how a successor tells its predecessors apart. */
  public static final String VERSION_LABEL = "qits.ci.runner.version";

  /** Every runner container is {@code qits-ci-runner-<first 8 of the runner id>-<version>}. */
  public static final String CONTAINER_PREFIX = "qits-ci-runner-";

  /** The one variable a successor is never given: the token was spent by the first start. */
  public static final String REGISTRATION_TOKEN_ENV = "QITS_CI_RUNNER_REGISTRATION_TOKEN";

  /**
   * {@code docker inspect}'s view of a runner container, narrowed by a template to exactly what a
   * successor inherits. Narrowed rather than the whole document because {@link Docker} keeps only
   * the tail of a stream, and a full inspect runs past that on any container with a few mounts.
   */
  static final String SELF_FORMAT =
      "{\"id\":{{json .Id}},\"image\":{{json .Image}},\"env\":{{json .Config.Env}},"
          + "\"labels\":{{json .Config.Labels}},\"binds\":{{json .HostConfig.Binds}},"
          + "\"mounts\":{{json .HostConfig.Mounts}},\"restart\":{{json .HostConfig.RestartPolicy}},"
          + "\"network\":{{json .HostConfig.NetworkMode}}}";

  /** The image's own environment and labels, which a successor does not carry across. */
  static final String IMAGE_CONFIG_FORMAT =
      "{\"env\":{{json .Config.Env}},\"labels\":{{json .Config.Labels}}}";

  /** One line per runner container: id, version label and state. */
  static final String PROCESS_FORMAT = "{{.ID}}|{{.Label \"" + VERSION_LABEL + "\"}}|{{.State}}";

  private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
  private static final Pattern IMAGE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._:/@+-]{0,511}");
  private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,255}");
  private static final Pattern LABEL_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}");
  private static final Pattern ADD_HOST = Pattern.compile("[A-Za-z0-9._-]{1,253}:[A-Za-z0-9.:_-]{1,253}");
  private static final Pattern USER = Pattern.compile("[A-Za-z0-9._-]{1,64}(:[A-Za-z0-9._-]{1,64})?");
  private static final Pattern SIZE = Pattern.compile("[0-9]{1,15}[bkmgBKMG]?");
  private static final Pattern CPUS = Pattern.compile("[0-9]{1,4}(\\.[0-9]{1,3})?");
  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

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

  /**
   * {@link #imageInspect(String, String)} under the docker client config in {@code configDir}, or
   * exactly that argv when it is null — see {@link #pull(String, Path, String)}.
   */
  public static List<String> imageInspect(String dockerBinary, Path configDir, String image) {
    return withConfig(imageInspect(dockerBinary, image), configDir);
  }

  /**
   * {@link #pull(String, String)} under the docker client config in {@code configDir} — the launch's
   * own registry login, {@code docker --config <dir>} being the one way to hand a single docker
   * invocation a credential without writing it into the host's config — or exactly that argv when it
   * is null.
   */
  public static List<String> pull(String dockerBinary, Path configDir, String image) {
    return withConfig(pull(dockerBinary, image), configDir);
  }

  /** {@code --config} is a global option, so it goes between the binary and the subcommand. */
  private static List<String> withConfig(List<String> argv, Path configDir) {
    if (configDir == null) {
      return argv;
    }
    List<String> configured = new ArrayList<>(argv.size() + 2);
    configured.add(argv.getFirst());
    configured.add("--config");
    configured.add(configDir.toString());
    configured.addAll(argv.subList(1, argv.size()));
    return List.copyOf(configured);
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

  /**
   * The name a runner container of {@code version} carries: {@code qits-ci-runner-<first 8 of the
   * id>-<version>}. The install script composes the same string, so a re-paste and a rollover agree
   * about which container is which.
   */
  public static String containerName(String runnerId, String version) {
    String id = require(NAME, "runner id", runnerId);
    return require(
        NAME,
        "container name",
        CONTAINER_PREFIX + id.substring(0, Math.min(8, id.length())) + "-" + requireVersion(version));
  }

  /** A runner version, as a container name and a label value both take it. */
  public static String requireVersion(String version) {
    return require(VERSION, "runner version", version);
  }

  /** The runner's own container, narrowed to what a successor inherits — see {@link #SELF_FORMAT}. */
  public static List<String> inspectSelf(String dockerBinary, String containerId) {
    return List.of(
        dockerBinary, "inspect", "--format", SELF_FORMAT, require(NAME, "container id", containerId));
  }

  /** The environment and labels an image brings by itself. */
  public static List<String> imageConfig(String dockerBinary, String image) {
    return List.of(
        dockerBinary, "image", "inspect", "--format", IMAGE_CONFIG_FORMAT, requireImage(image));
  }

  /** The registry digests a pulled image is known under — what an expected digest is checked in. */
  public static List<String> repoDigests(String dockerBinary, String image) {
    return List.of(
        dockerBinary, "image", "inspect", "--format", "{{json .RepoDigests}}", requireImage(image));
  }

  /** Where a container is in its life: {@code running}, {@code exited}, {@code restarting}, … */
  public static List<String> state(String dockerBinary, String containerId) {
    return List.of(
        dockerBinary,
        "inspect",
        "--format",
        "{{.State.Status}}",
        require(NAME, "container id", containerId));
  }

  /**
   * Take the restart policy off a container, so the exit it is about to make is its last. The
   * retiring runner's own step out of the way of its successor.
   */
  public static List<String> noRestart(String dockerBinary, String containerId) {
    return List.of(
        dockerBinary, "update", "--restart=no", require(NAME, "container id", containerId));
  }

  /**
   * Every runner container of this runner id — {@link #PROCESS_LABEL}, never the step label — one
   * line each in {@link #PROCESS_FORMAT}. The filter is the last element, as the sweep's is.
   */
  public static List<String> psProcess(String dockerBinary, String runnerId) {
    return List.of(
        dockerBinary,
        "ps",
        "-a",
        "--format",
        PROCESS_FORMAT,
        "--filter",
        "label=" + PROCESS_LABEL + "=" + require(NAME, "runner id", runnerId));
  }

  /**
   * The successor's whole {@code docker run}: the container contract, with everything that does not
   * depend on the image taken from the running container ({@code self}) and only the name, the image
   * and the version label new.
   *
   * <ul>
   *   <li>The restart policy and the mounts are carried as they are — the docker socket and the
   *       state volume holding {@code client.json}, on the contract's container, and whatever an
   *       operator added.
   *   <li>The network is carried only when it is not docker's default bridge, which the contract
   *       leaves unspelled.
   *   <li>The environment is the one the container was <em>given</em>, not the one the old image
   *       brought ({@link SelfSpec} subtracts that), and never {@value #REGISTRATION_TOKEN_ENV}: a
   *       successor starts registered.
   *   <li>Labels likewise, with {@link #PROCESS_LABEL} and {@link #VERSION_LABEL} set here and the
   *       step labels ({@link #RUNNER_LABEL}, {@link #RUN_LABEL}) dropped should the old container
   *       somehow carry one — a runner the sweep can match removes itself.
   * </ul>
   *
   * Every element is its own argument and nothing is a shell line, so a carried value is data.
   */
  public static List<String> runSuccessor(
      String dockerBinary, String runnerId, String version, String image, SelfSpec self) {
    List<String> argv = new ArrayList<>();
    argv.add(dockerBinary);
    argv.add("run");
    argv.add("-d");
    argv.add("--name");
    argv.add(containerName(runnerId, version));
    argv.add("--restart");
    argv.add(oneLine("restart policy", self.restart()));
    if (self.network() != null
        && !self.network().isEmpty()
        && !self.network().equals("default")
        && !self.network().equals("bridge")) {
      argv.add("--network");
      argv.add(require(NAME, "network", self.network()));
    }
    Map<String, String> labels = new TreeMap<>();
    for (Map.Entry<String, String> label : self.labels().entrySet()) {
      String key = label.getKey();
      if (key.equals(RUNNER_LABEL) || key.equals(RUN_LABEL)) {
        continue;
      }
      labels.put(require(LABEL_KEY, "label key", key), oneLine("label value", label.getValue()));
    }
    labels.put(PROCESS_LABEL, require(NAME, "runner id", runnerId));
    labels.put(VERSION_LABEL, requireVersion(version));
    for (Map.Entry<String, String> label : labels.entrySet()) {
      argv.add("--label");
      argv.add(label.getKey() + "=" + label.getValue());
    }
    for (String bind : self.binds()) {
      argv.add("-v");
      argv.add(oneLine("bind", bind));
    }
    for (String mount : self.mounts()) {
      argv.add("--mount");
      argv.add(oneLine("mount", mount));
    }
    for (Map.Entry<String, String> variable : new TreeMap<>(self.env()).entrySet()) {
      if (variable.getKey().equals(REGISTRATION_TOKEN_ENV)) {
        continue;
      }
      argv.add("-e");
      argv.add(
          require(ENV_KEY, "environment key", variable.getKey())
              + "="
              + oneLine("environment value", variable.getValue()));
    }
    argv.add(requireImage(image));
    return List.copyOf(argv);
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

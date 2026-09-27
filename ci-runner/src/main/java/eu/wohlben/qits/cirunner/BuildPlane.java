package eu.wohlben.qits.cirunner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The runner's own buildkitd — qits-containers' {@code PlatformBuildkit}, moved onto the runner's
 * host, because a step there cannot reach the platform's builder and should not need the host's
 * docker socket to build an image.
 *
 * <p><b>Ensured on first need, not at boot.</b> A runner that only ever runs test steps should not
 * pay for a privileged container it never uses; the first {@code Launch} whose spec asks for the
 * build plane (or the docker socket, which is a builder whoever holds it) brings it up, and every
 * later one finds it running. Each call re-inspects rather than caching "done", so a builder somebody
 * removed by hand is recreated by the next build instead of failing it.
 *
 * <p><b>Its names are the runner's, not the platform's.</b> The container is {@value #CONTAINER},
 * never {@code qits-buildkitd}: a runner on the platform host (the INTERNAL plane this epic tests on)
 * shares its docker with qits-containers, and a runner that "converged" the platform's builder onto
 * its own stamp would replace the builder every platform build uses. The state volume is {@value
 * #STATE_VOLUME}, the name the brief asks for — shared with the platform's builder on that one host,
 * which costs nothing but a warm cache — and the network is the runner-owned bridge {@value
 * #NETWORK}.
 *
 * <p><b>Ownership is the stamp label.</b> A container under the name carrying {@value #STAMP_LABEL}
 * is this runner's to adopt, start or replace; one without it is somebody else's and is refused, not
 * touched. The stamp is a hash over the image pin, the rendered toml and the boot prelude, so a bumped
 * pin <em>or a changed registry setting</em> replaces the container and the cache volume rides
 * across — the toml is as load-bearing as the pin (a builder whose registry config is wrong builds
 * nothing), which is the lesson PlatformBuildkit's stamp was written after.
 *
 * <p><b>The toml reaches the container as an environment value it writes for itself</b>, exactly as
 * PlatformBuildkit delivers it, rather than as a bind-mounted file: a bind needs a host path the
 * runner writes and docker reads, which is a host-path mount this runner otherwise never makes, and a
 * file the stamp does not see. The value is inside the {@code docker run}, so it is inside the
 * stamp by construction.
 */
public final class BuildPlane {

  private static final Logger LOG = Logger.getLogger(BuildPlane.class);

  /** The runner-owned bridge a building step and the builder share. */
  public static final String NETWORK = "qits-ci-runner";

  /** The builder's container name — also its address on {@link #NETWORK}. */
  public static final String CONTAINER = "qits-ci-runner-buildkitd";

  /** Where the builder keeps its content store across replacements. */
  public static final String STATE_VOLUME = "qits-buildkitd-state";

  /** What a building step is handed as {@code BUILDKIT_HOST} when its spec left the key absent. */
  public static final String ADDRESS = "tcp://" + CONTAINER + ":1234";

  /** The env key every consumer reads. */
  public static final String BUILDKIT_HOST = "BUILDKIT_HOST";

  /** The configuration stamp, and the mark of ownership — see the class javadoc. */
  public static final String STAMP_LABEL = "qits.ci.runner.buildkitd";

  /**
   * qits-containers' {@code BUILDKITD_BOOTSTRAP}, verbatim: the toml arrives as an environment
   * value and the container writes it for itself, and buildkitd listens on tcp for the steps and on
   * its default unix socket for anything exec'd into it.
   */
  static final String BOOTSTRAP =
      """
      set -e
      mkdir -p /etc/buildkit
      printf '%s' "$BUILDKITD_TOML" > /etc/buildkit/buildkitd.toml
      exec buildkitd --addr unix:///run/buildkit/buildkitd.sock --addr tcp://0.0.0.0:1234
      """;

  /**
   * What an exec resolves names with: docker's embedded DNS, which serves the network aliases a build
   * dials. qits-containers' {@code qits.containers.buildkit.exec-nameservers} default, and the only
   * value that makes sense for a builder whose {@code RUN}s live in its own container's namespace.
   */
  static final String EXEC_NAMESERVER = "127.0.0.11";

  private final Docker docker;
  private final String dockerBinary;
  private final String image;
  private final String toml;

  /** A builder with no registry configuration — every registry https and publicly resolvable. */
  public BuildPlane(Docker docker, String dockerBinary, String image) {
    this(docker, dockerBinary, image, List.of(), List.of());
  }

  /**
   * @param httpRegistries {@code host[:port]} entries buildkitd speaks plain HTTP to — the
   *     platform's own registry and mirror aliases, for a runner on {@code qits-net}.
   * @param registryMirrors {@code from=to} pairs; {@code to} may carry a path prefix. The platform's
   *     committed Dockerfiles name edge vhosts in their {@code FROM} lines, and this is what rewrites
   *     them to the in-network aliases the builder can reach.
   */
  public BuildPlane(
      Docker docker,
      String dockerBinary,
      String image,
      List<String> httpRegistries,
      List<String> registryMirrors) {
    this.docker = docker;
    this.dockerBinary = dockerBinary;
    this.image = image;
    this.toml = renderToml(httpRegistries, registryMirrors);
  }

  /**
   * buildkitd.toml, rendered rather than shipped — PlatformBuildkit's {@code buildkitdToml}, with its
   * measured reasons. {@code networkMode = "host"} puts a {@code RUN} in buildkitd's own network
   * namespace, which is on the runner network and on every step network it joined, so an in-network
   * name a build dials resolves; the {@code [dns]} override keeps BuildKit from filtering docker's
   * loopback resolver out of each exec's resolv.conf. Then one {@code [registry."host"]} table per
   * host, carrying its mirrors and/or {@code http = true}.
   *
   * <p><b>One table per host, merged</b>, which is where this departs from PlatformBuildkit: that one
   * appends a mirrors table and an http table independently, and a host named in both lists would be
   * a TOML file declaring the same table twice — which buildkitd refuses to start on. Here a host
   * that is both a mirror source and plain-HTTP gets both keys in one table. Order is the order the
   * operator wrote, so the rendering (and with it the stamp) is stable.
   */
  static String renderToml(List<String> httpRegistries, List<String> registryMirrors) {
    Map<String, List<String>> mirrors = new LinkedHashMap<>();
    Set<String> http = new LinkedHashSet<>(httpRegistries);
    Set<String> hosts = new LinkedHashSet<>();
    for (String pair : registryMirrors) {
      int split = pair.indexOf('=');
      String from = pair.substring(0, split);
      mirrors.computeIfAbsent(from, k -> new ArrayList<>()).add(pair.substring(split + 1));
      hosts.add(from);
    }
    hosts.addAll(http);
    StringBuilder out =
        new StringBuilder("[worker.oci]\n  networkMode = \"host\"\n  gc = true\n")
            .append("[dns]\n  nameservers = [\"").append(EXEC_NAMESERVER).append("\"]\n");
    for (String host : hosts) {
      out.append("[registry.\"").append(host).append("\"]\n");
      List<String> targets = mirrors.get(host);
      if (targets != null) {
        out.append("  mirrors = [");
        for (int i = 0; i < targets.size(); i++) {
          out.append(i == 0 ? "\"" : ", \"").append(targets.get(i)).append("\"");
        }
        out.append("]\n");
      }
      if (http.contains(host)) {
        out.append("  http = true\n");
      }
    }
    return out.toString();
  }

  /** The configuration this builder is started with. */
  String toml() {
    return toml;
  }

  /**
   * Make sure the builder is up, and reachable from {@code stepNetwork} too when the step names one.
   * Answers the failure's detail, or empty when the step may launch. Synchronized: two building
   * steps launched at once must not both {@code docker run} the one name.
   */
  public synchronized Optional<String> ensure(String stepNetwork) {
    Docker.Result network = docker.run(List.of(dockerBinary, "network", "inspect", NETWORK));
    if (!network.ok()) {
      Docker.Result created =
          docker.run(
              List.of(
                  dockerBinary, "network", "create", "--driver", "bridge", "--label",
                  STAMP_LABEL + "=network", NETWORK));
      if (!created.ok()) {
        return Optional.of("could not create the runner network " + NETWORK + ": " + created.detail());
      }
    }
    // Creating a volume that exists is docker's own no-op.
    Docker.Result volume = docker.run(List.of(dockerBinary, "volume", "create", STATE_VOLUME));
    if (!volume.ok()) {
      return Optional.of("could not create " + STATE_VOLUME + ": " + volume.detail());
    }
    String stamp = stamp();
    Docker.Result inspected =
        docker.run(
            List.of(
                dockerBinary,
                "inspect",
                "--format",
                "{{index .Config.Labels \"" + STAMP_LABEL + "\"}}|{{.State.Status}}",
                CONTAINER));
    if (inspected.ok()) {
      String[] answer = inspected.stdout().strip().split("\\|", 2);
      String running = answer[0];
      String status = answer.length > 1 ? answer[1] : "";
      if (running.isEmpty() || running.equals("<no value>")) {
        return Optional.of(
            "a container named " + CONTAINER + " exists and is not this runner's builder;"
                + " it was left alone");
      }
      if (running.equals(stamp)) {
        if (!status.equals("running")) {
          Docker.Result started = docker.run(List.of(dockerBinary, "start", CONTAINER));
          if (!started.ok()) {
            return Optional.of("could not start " + CONTAINER + ": " + started.detail());
          }
        }
        return connect(stepNetwork);
      }
      LOG.infof("ci-runner replacing its builder: stamp %s, configured %s", running, stamp);
      docker.run(RunnerArgv.rm(dockerBinary, CONTAINER));
    }
    if (!docker.run(RunnerArgv.imageInspect(dockerBinary, image)).ok()) {
      Docker.Result pulled = docker.run(RunnerArgv.pull(dockerBinary, image));
      if (!pulled.ok()) {
        return Optional.of("could not pull the builder image " + image + ": " + pulled.detail());
      }
    }
    Docker.Result started = docker.run(runArgv(stamp));
    if (!started.ok()) {
      return Optional.of("could not run the builder: " + started.detail());
    }
    LOG.infof("ci-runner builder %s is up on %s", CONTAINER, image);
    return connect(stepNetwork);
  }

  /**
   * The one privileged {@code docker run} this runner makes, and a fixed argv for the reason
   * DockerArgv's {@code runBuildkitd} is: {@code --privileged} must never be something a spec can
   * express. buildkitd cannot mount overlayfs for its sandboxes without it.
   */
  List<String> runArgv(String stamp) {
    return List.of(
        dockerBinary,
        "run",
        "-d",
        "--name",
        CONTAINER,
        "--network",
        NETWORK,
        "--label",
        STAMP_LABEL + "=" + stamp,
        "--privileged",
        "--restart",
        "unless-stopped",
        "-v",
        STATE_VOLUME + ":/var/lib/buildkit",
        "-e",
        "BUILDKITD_TOML=" + toml,
        "--entrypoint",
        "/bin/sh",
        RunnerArgv.requireImage(image),
        "-c",
        BOOTSTRAP);
  }

  /**
   * Join the step's own network too, so a build's pulls and pushes resolve that plane's names. A
   * second connect answers "already exists", which is the state asked for.
   */
  private Optional<String> connect(String stepNetwork) {
    if (stepNetwork == null || stepNetwork.equals(NETWORK)) {
      return Optional.empty();
    }
    Docker.Result joined =
        docker.run(List.of(dockerBinary, "network", "connect", stepNetwork, CONTAINER));
    if (!joined.ok() && !joined.detail().contains("already exists")) {
      return Optional.of(
          "could not connect " + CONTAINER + " to " + stepNetwork + ": " + joined.detail());
    }
    return Optional.empty();
  }

  String stamp() {
    String material = image + "\n" + toml + "\n" + BOOTSTRAP;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from this JVM", e);
    }
  }
}

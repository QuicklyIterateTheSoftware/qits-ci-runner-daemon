package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.cirunner.protocol.CiRunnerBinary;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Entry point, and <b>the one CDI shell</b> — qits-ci-daemon's arrangement: it resolves
 * configuration, news up the plain classes that do the work, and hands the exit code back. Every
 * setting a class needs arrives as a constructor argument from here, and nothing below reads
 * configuration itself.
 */
@QuarkusMain
public class Main {

  private static final Logger LOG = Logger.getLogger(Main.class);

  /**
   * {@code QITS_CI_RUNNER_PRINT_VERSION=1}: print {@link CiRunnerBinary#VERSION} and exit 0 — the
   * image's smoke probe. Answered before Quarkus starts, so it needs no other variable and prints
   * exactly one line to stdout with no log line beside it.
   */
  static final String PRINT_VERSION = "QITS_CI_RUNNER_PRINT_VERSION";

  /**
   * Read raw rather than through config, because its empty value means something: set and empty
   * switches the log export off, where unset derives it (see {@link RunnerEnv#telemetryUrl}) — and
   * SmallRye reads an empty value as no value at all.
   */
  static final String TELEMETRY_URL = "QITS_CI_RUNNER_TELEMETRY_URL";

  public static void main(String... args) {
    if (printsVersion(System.getenv(PRINT_VERSION))) {
      System.out.println(CiRunnerBinary.VERSION);
      return;
    }
    String decommission = System.getenv(RunnerArgv.DECOMMISSION_ENV);
    if (decommission != null && !decommission.isBlank()) {
      System.exit(decommissionHelper(decommission.strip()));
    }
    Quarkus.run(RunnerApplication.class, args);
  }

  /**
   * Helper mode: this process is a deleted runner's decommission helper ({@link Decommission}), not
   * a runner. Like the version probe it runs before Quarkus, needs no other variable, and writes
   * plain lines — the helper's {@code docker logs} is the whole record of what it did.
   */
  static int decommissionHelper(String runnerId) {
    String binary = System.getenv("QITS_CI_RUNNER_DOCKER_BINARY");
    String volume = System.getenv(RunnerArgv.DECOMMISSION_VOLUME_ENV);
    try {
      return Decommission.finish(
          Docker.forking(120),
          binary == null || binary.isBlank() ? "docker" : binary.strip(),
          runnerId,
          volume == null || volume.isBlank() ? null : volume.strip(),
          SelfContainer.detect(),
          Decommission.Settings.defaults(),
          line -> System.out.println("qits-ci-runner decommission: " + line));
    } catch (RuntimeException e) {
      System.out.println("qits-ci-runner decommission: failed: " + e.getMessage());
      return 1;
    }
  }

  /** {@code 1} (or {@code true}) asks for the version; anything else, unset included, does not. */
  static boolean printsVersion(String value) {
    return value != null && (value.strip().equals("1") || value.strip().equalsIgnoreCase("true"));
  }

  public static class RunnerApplication implements QuarkusApplication {

    @Inject Vertx vertx;

    // All Optional<String> and parsed by RunnerEnv: an empty `defaultValue` is read by SmallRye as
    // *no value* and kills the binary at startup with a message about config rather than about the
    // missing environment, and a typed field would do the same for "slots=two".
    @ConfigProperty(name = "qits.ci.runner.url")
    Optional<String> url;

    @ConfigProperty(name = "qits.ci.runner.id")
    Optional<String> id;

    @ConfigProperty(name = "qits.ci.runner.registration-token")
    Optional<String> registrationToken;

    @ConfigProperty(name = "qits.ci.runner.state-dir")
    Optional<String> stateDir;

    @ConfigProperty(name = "qits.ci.runner.slots")
    Optional<String> slots;

    @ConfigProperty(name = "qits.ci.runner.docker-binary")
    Optional<String> dockerBinary;

    @ConfigProperty(name = "qits.ci.runner.docker-timeout")
    Optional<String> dockerTimeout;

    @ConfigProperty(name = "qits.ci.runner.buildkit-image")
    Optional<String> buildkitImage;

    @ConfigProperty(name = "qits.ci.runner.buildkit-http-registries")
    Optional<String> buildkitHttpRegistries;

    @ConfigProperty(name = "qits.ci.runner.buildkit-registry-mirrors")
    Optional<String> buildkitRegistryMirrors;

    @ConfigProperty(name = "qits.ci.runner.rollover-timeout")
    Optional<String> rolloverTimeout;

    @ConfigProperty(name = "qits.ci.runner.heartbeat-interval-ms", defaultValue = "10000")
    long heartbeatMillis;

    @ConfigProperty(name = "qits.ci.runner.reconnect-initial-backoff-ms", defaultValue = "500")
    long initialBackoffMillis;

    @ConfigProperty(name = "qits.ci.runner.reconnect-max-backoff-ms", defaultValue = "30000")
    long maxBackoffMillis;

    @ConfigProperty(name = "qits.ci.runner.http-timeout-ms", defaultValue = "30000")
    long httpTimeoutMillis;

    @Override
    public int run(String... args) {
      RunnerEnv env;
      String telemetryUrl;
      try {
        env =
            RunnerEnv.parse(
                url.orElse(null),
                id.orElse(null),
                registrationToken.orElse(null),
                stateDir.orElse(null),
                slots.orElse(null),
                dockerBinary.orElse(null),
                dockerTimeout.orElse(null),
                buildkitImage.orElse(null),
                buildkitHttpRegistries.orElse(null),
                buildkitRegistryMirrors.orElse(null),
                rolloverTimeout.orElse(null));
        telemetryUrl = RunnerEnv.telemetryUrl(System.getenv(TELEMETRY_URL), env.url());
      } catch (RunnerEnv.Invalid invalid) {
        // The container's log is the only channel before anything is dialled, so this line is the whole
        // diagnosis an operator gets. It names the variable, never a value that could be a secret.
        LOG.errorf("ci-runner cannot start: %s. Exiting.", invalid.getMessage());
        return ExitCode.MISCONFIGURED;
      }
      Docker docker = Docker.forking(env.dockerTimeoutSeconds());
      Http http = new Http(vertx, httpTimeoutMillis);
      Optional<String> self = SelfContainer.detect();
      Capabilities capabilities = capabilities();
      Telemetry telemetry =
          telemetryUrl == null
              ? Telemetry.off()
              : new Telemetry(
                  vertx,
                  http,
                  telemetryUrl,
                  telemetryResource(env.runnerId(), self, capabilities),
                  CiRunnerBinary.VERSION,
                  Telemetry.Settings.defaults(),
                  () -> System.currentTimeMillis() * 1_000_000L);
      // On the root logger, so every line this process writes is shipped — queued from here, sent
      // once the runner has a bearer.
      java.util.logging.Logger.getLogger("").addHandler(telemetry);
      if (self.isPresent()) {
        LOG.infof("ci-runner %s is running in container %s", CiRunnerBinary.VERSION, self.get());
      }
      if (telemetryUrl == null) {
        LOG.infof(
            "ci-runner ships its log nowhere (%s is %s)",
            TELEMETRY_URL,
            System.getenv(TELEMETRY_URL) == null
                ? "unset and " + env.url() + " has no ci. host to derive it from"
                : "empty");
      }
      BuildPlane buildPlane =
          new BuildPlane(
              docker,
              env.dockerBinary(),
              env.buildkitImage(),
              env.buildkitHttpRegistries(),
              env.buildkitRegistryMirrors(),
              caBundleCandidates(self));
      RunnerMain runner =
          new RunnerMain(
              vertx,
              env,
              new RunnerMain.Parts(
                  new Registration(http, httpTimeoutMillis),
                  client ->
                      new ControlSocket.Settings(
                          heartbeatMillis, initialBackoffMillis, maxBackoffMillis),
                  new BootSweep(docker, env.dockerBinary(), env.runnerId()),
                  new Launcher(docker, env.dockerBinary(), env.runnerId(), buildPlane),
                  new Reaper(docker, env.dockerBinary(), env.runnerId()),
                  capabilities,
                  client -> new Bearer(http, client, System::currentTimeMillis),
                  (client, held) ->
                      new Rollover(
                          docker,
                          env.dockerBinary(),
                          env.runnerId(),
                          CiRunnerBinary.VERSION,
                          self,
                          Rollover.Settings.defaults(env.rolloverTimeoutSeconds()),
                          client,
                          held),
                  telemetry,
                  new Decommission(
                      docker,
                      env.dockerBinary(),
                      env.runnerId(),
                      self,
                      env.stateDir(),
                      Decommission.Settings.defaults())));
      return runner.run();
    }
  }

  /**
   * Where the builder's host CA bundle is probed — nowhere, in a container. The probe checks this
   * process's filesystem and the bind it produces is resolved by the host's docker daemon on the
   * host's: inside the runner's image the probe would always find the image's own Alpine bundle, and
   * a host that keeps its bundle elsewhere (RHEL's {@code /etc/pki/…}) would get an empty directory
   * created at the Debian path and mounted over the builder's certificates. The builder's own bundle
   * validates every publicly-issued chain, which is the platform's edge; an operator root on the host
   * is not reachable from here, and the README says so.
   */
  static List<String> caBundleCandidates(Optional<String> self) {
    return self.isPresent() ? List.of() : BuildPlane.HOST_CA_BUNDLE_CANDIDATES;
  }

  /**
   * Who is speaking, on every shipped line: one bucket, {@link Telemetry#SERVICE_NAME}, for every
   * runner, told apart by {@code qits.ci.runner.id} — the id an operator sees in the CI's runner list
   * — and by the container it runs in. The runner's display name is the CI's and is never sent to
   * the runner, so it is not here.
   */
  static Map<String, String> telemetryResource(
      String runnerId, Optional<String> self, Capabilities capabilities) {
    Map<String, String> resource = new java.util.LinkedHashMap<>();
    resource.put("service.name", Telemetry.SERVICE_NAME);
    resource.put("service.version", CiRunnerBinary.VERSION);
    resource.put("service.instance.id", self.orElse(runnerId));
    resource.put("qits.ci.runner.id", runnerId);
    resource.put("host.arch", capabilities.arch());
    resource.put("os.type", capabilities.os());
    return resource;
  }

  /**
   * What this host can do. {@code docker} is whether the socket a {@code docker: true} step would be
   * handed exists here — the one capability qits-ci matches on. The platform is spelled the way an
   * image's is ({@code amd64}, {@code arm64}, {@code linux}), so a later match needs no table.
   */
  static Capabilities capabilities() {
    String arch =
        switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
          case "amd64", "x86_64" -> "amd64";
          case "aarch64", "arm64" -> "arm64";
          case String other -> other;
        };
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    boolean docker = Files.exists(Path.of(RunnerArgv.DOCKER_SOCKET));
    return new Capabilities(docker, arch, os, Map.of());
  }
}

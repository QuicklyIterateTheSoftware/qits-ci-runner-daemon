package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.Capabilities;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
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

  public static void main(String... args) {
    Quarkus.run(RunnerApplication.class, args);
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
                buildkitRegistryMirrors.orElse(null));
      } catch (RunnerEnv.Invalid invalid) {
        // The journal is the only channel before anything is dialled, so this line is the whole
        // diagnosis an operator gets. It names the variable, never a value that could be a secret.
        LOG.errorf("ci-runner cannot start: %s. Exiting.", invalid.getMessage());
        return ExitCode.MISCONFIGURED;
      }
      Docker docker = Docker.forking(env.dockerTimeoutSeconds());
      Http http = new Http(vertx, httpTimeoutMillis);
      BuildPlane buildPlane =
          new BuildPlane(
              docker,
              env.dockerBinary(),
              env.buildkitImage(),
              env.buildkitHttpRegistries(),
              env.buildkitRegistryMirrors());
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
                  capabilities(),
                  client -> new Bearer(http, client, System::currentTimeMillis)));
      return runner.run();
    }
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

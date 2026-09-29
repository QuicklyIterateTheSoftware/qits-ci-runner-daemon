package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cirunner.protocol.Ack;
import eu.wohlben.qits.cirunner.protocol.Backlog;
import eu.wohlben.qits.cirunner.protocol.Cancel;
import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.cirunner.protocol.CiRunnerBinary;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol;
import eu.wohlben.qits.cirunner.protocol.Heartbeat;
import eu.wohlben.qits.cirunner.protocol.Hello;
import eu.wohlben.qits.cirunner.protocol.Launch;
import eu.wohlben.qits.cirunner.protocol.LaunchFailed;
import eu.wohlben.qits.cirunner.protocol.Launched;
import eu.wohlben.qits.cirunner.protocol.Nothing;
import eu.wohlben.qits.cirunner.protocol.Quarantined;
import eu.wohlben.qits.cirunner.protocol.Reap;
import eu.wohlben.qits.cirunner.protocol.Reaped;
import eu.wohlben.qits.cirunner.protocol.Reinstated;
import eu.wohlben.qits.cirunner.protocol.Released;
import eu.wohlben.qits.cirunner.protocol.Reserve;
import eu.wohlben.qits.cirunner.protocol.Retire;
import eu.wohlben.qits.cirunner.protocol.Take;
import eu.wohlben.qits.cirunner.protocol.Upgrade;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole runner against a real in-JVM qits-ci (register door, token endpoint, control socket)
 * and a shell script answering like docker. Real socket, real frames, real codec, real processes.
 */
@EnabledOnOs(OS.LINUX)
class RunnerMainTest {

  private final Vertx vertx = Vertx.vertx();
  private FakeHost host;
  private FakeDocker docker;
  private RunnerMain runner;
  private CompletableFuture<Integer> exit;

  @TempDir Path state;
  @TempDir Path dockerDir;

  private static final Capabilities CAPS = new Capabilities(true, "amd64", "linux", Map.of());

  /** The runner's own container, as HOSTNAME would name it. */
  private static final String SELF = "0123456789ab";

  private Optional<String> self = Optional.of(SELF);
  private Telemetry telemetry = Telemetry.off();
  private Rollover.Settings rolloverSettings = new Rollover.Settings(100, 400, 3_000, 3_000, 50);

  /** How long an invalid_client streak must last to be believed; production's is five minutes. */
  private long refusalConfirmMillis = 300_000;

  @BeforeEach
  void setUp() throws Exception {
    host = new FakeHost(vertx);
    docker = new FakeDocker(dockerDir);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runner != null) {
      runner.stop(ExitCode.OK);
      exit.get(10, TimeUnit.SECONDS);
    }
    host.close();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private CompletableFuture<Integer> start(String token, long heartbeatMillis) throws Exception {
    RunnerEnv env =
        RunnerEnv.parse(host.base(), "r1", token, state.toString(), "2", docker.binary, "10", null);
    Docker d = docker.docker(10);
    Http http = new Http(vertx, 5_000);
    runner =
        new RunnerMain(
            vertx,
            env,
            new RunnerMain.Parts(
                new Registration(http, 5_000),
                client ->
                    new ControlSocket.Settings(heartbeatMillis, 50, 200, refusalConfirmMillis, 3),
                new BootSweep(d, docker.binary, "r1"),
                new Launcher(d, docker.binary, "r1", new BuildPlane(d, docker.binary, "moby/buildkit:v0.33.0")),
                new Reaper(d, docker.binary, "r1"),
                CAPS,
                client -> new Bearer(http, client, System::currentTimeMillis),
                (client, held) ->
                    new Rollover(
                        d,
                        docker.binary,
                        "r1",
                        CiRunnerBinary.VERSION,
                        self,
                        rolloverSettings,
                        client,
                        held),
                telemetry,
                new Decommission(
                    d, docker.binary, "r1", self, state, new Decommission.Settings(200, 20, 2))));
    exit = CompletableFuture.supplyAsync(runner::run);
    return exit;
  }

  /** The default host: Ack every Hello with two slots and a backlog of one. */
  private void ackWith(int slots, int backlog) {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(CiRunnerProtocol.CAPABILITY_VERSION, slots));
            h.send(new Backlog(backlog));
          }
        };
  }

  @Test
  void anUnregisteredRunnerWithoutATokenExitsTwoBeforeDiallingAnything() throws Exception {
    int code = start(null, 10_000).get(20, TimeUnit.SECONDS);
    runner = null;
    assertEquals(ExitCode.MISCONFIGURED, code);
    assertEquals(0, host.upgrades.get());
    assertEquals(0, host.registerBodies.size());
  }

  @Test
  void itRegistersSweepsDialsWithTheBearerAndSaysHello() throws Exception {
    docker.answer("ps", 0, "leftover1\n", "");
    ackWith(2, 0);
    start("one-time-token", 10_000);

    Hello hello = host.await(Hello.class);
    assertEquals(
        new Hello(CiRunnerBinary.VERSION, CiRunnerProtocol.CAPABILITY_VERSION, 2, CAPS), hello);
    assertEquals("Bearer runner-access-token", host.upgradeHeaders.get("Authorization"));
    assertTrue(Files.exists(state.resolve("client.json")));
    // The sweep ran before the Hello, by the runner's own label only. (What follows the Ack is the
    // predecessor sweep, by the process label — see the rollover tests below.) The leftover's
    // output is read before it goes; ReaperTest.withoutReads leaves the calls that change things.
    assertEquals(
        List.of(
            List.of("ps", "-aq", "--filter", "label=qits.ci.runner=r1"),
            List.of("rm", "-f", "leftover1")),
        ReaperTest.withoutReads(docker.calls()).subList(0, 2));
  }

  @Test
  void aBacklogAsksForARunAndATakeAsksForTheNextWhileASlotIsFree() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(CiRunnerProtocol.CAPABILITY_VERSION, 2));
            h.send(new Backlog(3));
          } else if (message instanceof Reserve) {
            int reserves = h.all(Reserve.class).size();
            h.send(new Take("run-" + reserves, "repo", "main", "abc"));
          }
        };
    start("t", 10_000);

    host.await(Reserve.class, 2);
    Thread.sleep(300);
    assertEquals(2, host.all(Reserve.class).size(), "two slots, two runs, no third Reserve");
  }

  @Test
  void nothingWaitsForTheNextBacklogAndAReleasedRunFreesItsSlot() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(CiRunnerProtocol.CAPABILITY_VERSION, 1));
            h.send(new Backlog(1));
          } else if (message instanceof Reserve) {
            if (h.all(Reserve.class).size() == 1) {
              h.send(new Nothing());
            } else {
              h.send(new Take("run-a", "repo", "main", "abc"));
            }
          }
        };
    start("t", 10_000);

    host.await(Reserve.class, 1);
    Thread.sleep(300);
    assertEquals(1, host.all(Reserve.class).size(), "Nothing parks the runner");
    host.send(new Backlog(2));
    host.await(Reserve.class, 2);
    Thread.sleep(300);
    assertEquals(2, host.all(Reserve.class).size(), "one slot, held by run-a");
    host.send(new Released("run-a"));
    host.await(Reserve.class, 3);
  }

  @Test
  void aLaunchIsAnsweredLaunchedAndAReapReapedWithTheContainersLastWords() throws Exception {
    docker.answer("run", 0, "c0ffee\n", "");
    docker.answer("inspect", 0, "exited 1\n", "");
    docker.answer("logs", 0, "dial ci: no such host\n", "");
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.send(new Launch("run-1", 0, WorkloadSpec.of("alpine:3", "qits-ci-run-1-x-0")));
    assertEquals(new Launched("run-1", 0, "c0ffee"), host.await(Launched.class));
    host.send(new Reap("run-1", 0, "qits-ci-run-1-x-0"));
    assertEquals(
        new Reaped("run-1", 0, "[container exited 1]\ndial ci: no such host\n"),
        host.await(Reaped.class),
        "the tail crosses the socket's JSON on the way to the host");

    List<String> run = docker.calls("run").getFirst();
    assertTrue(run.contains("qits.ci.runner=r1"), run::toString);
    assertTrue(
        docker.calls().stream().anyMatch(c -> c.equals(List.of("rm", "-f", "qits-ci-run-1-x-0"))));
  }

  /** A building step's spec — {@code build: true} — the way {@code BuildPlaneTest}'s builder needs. */
  private static WorkloadSpec building() {
    return new WorkloadSpec(
        "alpine:3", null, null, Map.of(), null, null, null, null, false, true, true, null, null,
        null, null, null, "qits-ci-run-1-x-0", true);
  }

  @Test
  void anAcksRegistryMirrorsReachTheBuilder() throws Exception {
    docker.answer("inspect", 1, "", "No such object");
    docker.answer("run", 0, "cid\n", "");
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(
                new Ack(
                    CiRunnerProtocol.CAPABILITY_VERSION,
                    1,
                    Map.of(
                        "mirror.dev.localhost:8080", "mirror.qits.wohlben.eu",
                        "registry.dev.localhost:8080", "registry.qits.wohlben.eu")));
          }
        };
    start("t", 10_000);
    host.await(Hello.class);
    // The Ack carried the map before any Launch — the builder is not up yet, only configured; a
    // build is what actually brings it up, under the merged toml.
    host.send(new Launch("run-1", 0, building()));
    assertEquals(new Launched("run-1", 0, "cid"), host.await(Launched.class));

    List<String> builder =
        docker.calls().stream()
            .filter(c -> c.get(0).equals("run") && c.contains("--privileged"))
            .findFirst()
            .orElseThrow();
    String toml = builder.get(builder.indexOf("-e") + 1);
    assertTrue(
        toml.contains(
            "[registry.\"mirror.dev.localhost:8080\"]\n  mirrors = [\"mirror.qits.wohlben.eu\"]"),
        toml);
    assertTrue(
        toml.contains(
            "[registry.\"registry.dev.localhost:8080\"]\n  mirrors = [\"registry.qits.wohlben.eu\"]"),
        toml);
  }

  /**
   * qits-ci re-sends {@code Ack} for reasons that have nothing to do with the mirror map — an admin
   * raising the slot cap, say — and the runner must pick up every one, not just the one that
   * answered its {@code Hello}. Shaped like {@code aBacklogAsksForARunAndATakeAsksForTheNextWhileASlotIsFree}:
   * a cap of 3 lets three Reserve/Take round trips happen with no Released between them, which a
   * cap still stuck at the first Ack's 1 could never do.
   */
  @Test
  void aLaterAckWithANewSlotsCapUpdatesTheRunnersCap() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(CiRunnerProtocol.CAPABILITY_VERSION, 1));
            h.send(new Ack(CiRunnerProtocol.CAPABILITY_VERSION, 3));
            h.send(new Backlog(3));
          } else if (message instanceof Reserve) {
            int reserves = h.all(Reserve.class).size();
            h.send(new Take("run-" + reserves, "repo", "main", "abc"));
          }
        };
    start("t", 10_000);

    host.await(Reserve.class, 3);
    Thread.sleep(300);
    assertEquals(3, host.all(Reserve.class).size(), "three slots, three runs, no fourth Reserve");
  }

  /**
   * The hop a TelemetryTest cannot see: the runner hands its export the bearer it dials with, and
   * the lines it logged before it had one — its registration — are shipped with it.
   */
  @Test
  void itShipsItsLogWithTheBearerItDialsWith() throws Exception {
    telemetry =
        new Telemetry(
            vertx,
            new Http(vertx, 5_000),
            host.base() + "/otel",
            Map.of("service.name", Telemetry.SERVICE_NAME),
            "test",
            new Telemetry.Settings(100, 50, 50),
            () -> 1L);
    java.util.logging.LogRecord registered =
        new java.util.logging.LogRecord(java.util.logging.Level.INFO, "logged before the bearer");
    telemetry.publish(registered);
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (host.telemetryBodies.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertEquals("Bearer runner-access-token", host.telemetryAuthorizations.getFirst());
    assertEquals(
        "logged before the bearer",
        OtlpDecode.decode(host.telemetryBodies.getFirst()).lines().getFirst().body());
  }

  @Test
  void aLaunchDockerRefusesIsAnsweredLaunchFailedWithItsWords() throws Exception {
    docker.answer("run", 125, "", "docker: Error response from daemon: Conflict. The container name is already in use.");
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.send(new Launch("run-1", 0, WorkloadSpec.of("alpine:3", "c0")));
    LaunchFailed failed = host.await(LaunchFailed.class);
    assertTrue(failed.detail().contains("already in use"), failed::detail);
  }

  @Test
  void aCancelRemovesTheRunsContainers() throws Exception {
    docker.answer("ps", 0, "", "");
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);
    docker.answer("ps", 0, "c1\n", "");

    host.send(new Cancel("run-9"));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (docker.calls("rm").isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(
        docker.calls().stream()
            .anyMatch(c -> c.contains("label=qits.ci.runner.run=run-9")));
    assertTrue(docker.calls().stream().anyMatch(c -> c.equals(List.of("rm", "-f", "c1"))));
  }

  @Test
  void anAckInAnotherCapabilityVersionEndsTheProcessNonzero() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(CiRunnerProtocol.CAPABILITY_VERSION + 1, 2));
          }
        };
    int code = start("t", 10_000).get(20, TimeUnit.SECONDS);
    runner = null;
    assertEquals(ExitCode.CAPABILITY_MISMATCH, code);
  }

  @Test
  void aLostSocketIsRedialledAndTheNewSessionStartsClean() throws Exception {
    ackWith(2, 0);
    start("t", 10_000);
    host.await(Hello.class, 1);
    host.send(new Backlog(1));
    host.await(Reserve.class);
    host.send(new Take("run-a", "repo", "main", "abc"));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (runner.reservations().held() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    host.socket.close();

    host.await(Hello.class, 2);
    assertEquals(2, host.upgrades.get());
    assertEquals(0, runner.reservations().held(), "the host failed run-a; nothing carries across");
    assertTrue(docker.calls("ps").size() >= 2, "each session sweeps first");
  }

  @Test
  void heartbeatsRunUnderneathTheSession() throws Exception {
    ackWith(1, 0);
    start("t", 100);
    host.await(Heartbeat.class, 3);
  }

  @Test
  void anUnknownFrameTypeIsDroppedAndTheSessionCarriesOn() throws Exception {
    // What a runner released before Upgrade existed does with one: its codec answers UNKNOWN_TYPE
    // and ControlSocket drops the frame. Shown with a type this codec does not know either.
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.sendRaw(
        "{\"type\":\"upgradeV2\",\"version\":\"2\",\"image\":\"r/qits/qits-ci-runner:2\"}");
    host.send(new Backlog(1));
    host.await(Reserve.class);
    assertEquals(1, host.upgrades.get(), "still the first session: nothing was dropped but the frame");
    assertFalse(exit.isDone());
  }

  // ---- self-update ------------------------------------------------------------------------------

  private static final String NEXT = "2026.999.1";
  private static final String IMAGE = "registry.example:5000/qits/qits-ci-runner:" + NEXT;
  private static final String DIGEST =
      "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String SUCCESSOR = "qits-ci-runner-r1-" + NEXT;

  /**
   * A fake docker that answers like the runner's own container: its inspect (what the install
   * script started), its old image's config, and the pulled image's digest.
   *
   * <p>Deliberately no {@code Mounts} key on the container at all — {@code HostConfig} carries
   * {@code Binds} only, which is docker's real shape for a container started with {@code -v} and
   * never {@code --mount}, and is exactly what broke the old {@code --format} template (qits-463):
   * {@code docker inspect -f '{{.HostConfig.Mounts}}'} refuses to render a key that is not there,
   * where reading the same document as JSON in Java answers an absent array as {@code null}.
   */
  private void runnerContainer() throws Exception {
    docker.answerFor(
        "inspect",
        SELF,
        0,
        new JsonArray()
                .add(
                    new JsonObject()
                        .put("Id", SELF + "0".repeat(52))
                        .put("Image", "sha256:old")
                        .put(
                            "Config",
                            new JsonObject()
                                .put(
                                    "Env",
                                    List.of(
                                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                                        "DOCKER_VERSION=29.8.1",
                                        "QITS_CI_RUNNER_URL=https://ci.qits.example.eu",
                                        "QITS_CI_RUNNER_ID=r1",
                                        "QITS_CI_RUNNER_SLOTS=2",
                                        "QITS_CI_RUNNER_REGISTRATION_TOKEN=qits_tok_SPENT"))
                                .put(
                                    "Labels",
                                    Map.of(
                                        "qits.ci.runner.process", "r1",
                                        "qits.ci.runner.version", CiRunnerBinary.VERSION,
                                        "org.opencontainers.image.version", "29.8.1")))
                        .put(
                            "HostConfig",
                            new JsonObject()
                                .put(
                                    "Binds",
                                    List.of(
                                        "/var/run/docker.sock:/var/run/docker.sock",
                                        "qits-ci-runner-state-r1:/var/lib/qits-ci-runner"))
                                .put(
                                    "RestartPolicy",
                                    Map.of("Name", "unless-stopped", "MaximumRetryCount", 0))
                                .put("NetworkMode", "default")))
                .encode()
            + "\n",
        "");
    docker.answerFor(
        "image-inspect",
        "sha256:old",
        0,
        new JsonArray()
                .add(
                    new JsonObject()
                        .put("Id", "sha256:old")
                        .put(
                            "Config",
                            new JsonObject()
                                .put(
                                    "Env",
                                    List.of(
                                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                                        "DOCKER_VERSION=29.8.1"))
                                .put(
                                    "Labels",
                                    Map.of("org.opencontainers.image.version", "29.8.1"))))
                .encode()
            + "\n",
        "");
    docker.answerFor(
        "image-inspect", IMAGE, 0, "[\"registry.example:5000/qits/qits-ci-runner@" + DIGEST + "\"]\n", "");
  }

  private static final List<String> SUCCESSOR_RUN =
      List.of(
          "run",
          "-d",
          "--name",
          SUCCESSOR,
          "--restart",
          "unless-stopped",
          "--label",
          "qits.ci.runner.process=r1",
          "--label",
          "qits.ci.runner.version=" + NEXT,
          "-v",
          "/var/run/docker.sock:/var/run/docker.sock",
          "-v",
          "qits-ci-runner-state-r1:/var/lib/qits-ci-runner",
          "-e",
          "QITS_CI_RUNNER_ID=r1",
          "-e",
          "QITS_CI_RUNNER_SLOTS=2",
          "-e",
          "QITS_CI_RUNNER_URL=https://ci.qits.example.eu",
          IMAGE);

  private void awaitCall(java.util.function.Predicate<List<List<String>>> condition)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.test(docker.calls()) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.test(docker.calls()), () -> "docker calls: " + calls());
  }

  /** Poll a runner-side condition set from the WebSocket's own thread, not the test's. */
  private void awaitTrue(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean());
  }

  private String calls() {
    try {
      return docker.calls().toString();
    } catch (Exception e) {
      return e.toString();
    }
  }

  private static boolean has(List<List<String>> calls, List<String> call) {
    return calls.stream().map(FakeDocker::withoutConfig).anyMatch(call::equals);
  }

  /** Take one run and hold it; answers once the runner counts it. */
  private void holdARun() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(CiRunnerProtocol.CAPABILITY_VERSION, 2));
            h.send(new Backlog(1));
          } else if (message instanceof Reserve) {
            h.send(new Take("run-a", "repo", "main", "abc"));
          }
        };
    start("t", 10_000);
    host.await(Reserve.class);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (runner.reservations().held() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(1, runner.reservations().held());
  }

  @Test
  void anUpgradeDrainsAndStartsTheSuccessorOnlyOnceTheLastHeldRunIsReleased() throws Exception {
    runnerContainer();
    holdARun();

    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    host.send(new Backlog(5));
    awaitCall(c -> has(c, List.of("pull", IMAGE)));
    Thread.sleep(300);
    assertEquals(1, host.all(Reserve.class).size(), "a draining runner reserves nothing");
    assertTrue(docker.calls("run").isEmpty(), "no successor while a run is held");

    host.send(new Released("run-a"));
    awaitCall(c -> !docker.callsQuietly("run").isEmpty());
    assertEquals(SUCCESSOR_RUN, docker.calls("run").getFirst());
    assertEquals(1, host.all(Reserve.class).size(), "a released slot is not refilled while draining");
    assertFalse(exit.isDone(), "the old process stays until it is retired");
  }

  @Test
  void theUpgradePullRunsUnderAThrowawayLoginMadeOfTheRunnersOwnClientPair() throws Exception {
    runnerContainer();
    ackWith(2, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.send(new Upgrade(NEXT, IMAGE, null));
    awaitCall(c -> !docker.callsQuietly("run").isEmpty());

    List<String> pull =
        docker.calls().stream().filter(c -> c.contains("pull")).findFirst().orElseThrow();
    assertEquals("--config", pull.get(0));
    assertEquals(List.of("pull", IMAGE), pull.subList(2, 4));
    assertFalse(Files.exists(Path.of(pull.get(1))), "the login directory is removed after the pull");
    List<String> config = docker.configs().getFirst();
    assertEquals("700 600", config.get(0), "directory 0700, config.json 0600");
    String auth =
        Base64.getEncoder()
            .encodeToString(
                "ci-runner-r1:client-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(
        new JsonObject()
            .put("auths", new JsonObject().put("registry.example:5000", new JsonObject().put("auth", auth))),
        new JsonObject(config.get(1)));
    assertTrue(
        docker.calls().stream().flatMap(List::stream).noneMatch(a -> a.contains("runner-access-token")),
        "the bearer is in no argv");
    assertTrue(
        docker.calls().stream().flatMap(List::stream).noneMatch(a -> a.contains("client-secret")),
        "the client secret is in no argv");
  }

  @Test
  void aPulledImageWithAnotherDigestIsNeverStartedAndThePullIsRetried() throws Exception {
    runnerContainer();
    docker.answerFor(
        "image-inspect", IMAGE, 0, "[\"registry.example:5000/qits/qits-ci-runner@sha256:beef\"]\n", "");
    ackWith(2, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.send(new Upgrade(NEXT, IMAGE, DIGEST.substring("sha256:".length())));
    awaitCall(c -> docker.callsQuietly("pull").size() >= 2);
    assertTrue(docker.calls("run").isEmpty(), "an image that is not the expected one is not run");
  }

  @Test
  void aRetireWithinTheWatchTakesTheRestartPolicyAwayAndExitsZero() throws Exception {
    runnerContainer();
    ackWith(2, 0);
    start("t", 10_000);
    host.await(Hello.class);
    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    awaitCall(c -> !docker.callsQuietly("run").isEmpty());

    host.send(new Retire("superseded by " + NEXT));
    assertEquals(ExitCode.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertTrue(has(docker.calls(), List.of("update", "--restart=no", SELF)), this::calls);
    List<List<String>> calls = docker.calls();
    assertFalse(
        calls.subList(calls.indexOf(SUCCESSOR_RUN) + 1, calls.size())
            .contains(List.of("rm", "-f", SUCCESSOR)),
        "the successor is left running");
  }

  @Test
  void aSuccessorThatDoesNotTakeOverIsRemovedAndTheRunnerStaysDrainingAndRetries()
      throws Exception {
    rolloverSettings = new Rollover.Settings(100, 400, 300, 3_000, 50);
    runnerContainer();
    ackWith(2, 0);
    start("t", 10_000);
    host.await(Hello.class);
    host.send(new Upgrade(NEXT, IMAGE, DIGEST));

    // Started, not retired within the watch, removed — and started again on the retry.
    awaitCall(c -> docker.callsQuietly("run").size() >= 2);
    List<List<String>> calls = docker.calls();
    int firstRun = calls.indexOf(SUCCESSOR_RUN);
    assertTrue(
        calls.subList(firstRun + 1, calls.size()).contains(List.of("rm", "-f", SUCCESSOR)),
        this::calls);
    assertFalse(has(calls, List.of("update", "--restart=no", SELF)), "it never took itself out");
    host.send(new Backlog(3));
    Thread.sleep(300);
    assertTrue(host.all(Reserve.class).isEmpty(), "still draining");
    assertFalse(exit.isDone());
  }

  @Test
  void aRetireWhileTheUpgradeHasNoSuccessorRunningIsIgnored() throws Exception {
    runnerContainer();
    holdARun();
    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    awaitCall(c -> has(c, List.of("pull", IMAGE)));

    host.send(new Retire("a runner of " + NEXT + " said Hello"));
    Thread.sleep(500);
    assertFalse(exit.isDone(), "leaving now would leave the host with no runner at all");
    assertFalse(has(docker.calls(), List.of("update", "--restart=no", SELF)));
  }

  @Test
  void aRetireWithNoUpgradeAskedForIsTheSameOrderlyExit() throws Exception {
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.send(new Retire("decommissioned"));
    assertEquals(ExitCode.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertTrue(has(docker.calls(), List.of("update", "--restart=no", SELF)), this::calls);
    assertNoDecommission();
  }

  // --- a deleted runner ---------------------------------------------------------------------------

  private static final List<String> DECOMMISSIONER_RUN =
      List.of(
          "run",
          "-d",
          "--name",
          "qits-ci-runner-r1-decommission",
          "--network",
          "none",
          "--label",
          "qits.ci.runner.decommission=r1",
          "-v",
          "/var/run/docker.sock:/var/run/docker.sock",
          "-e",
          "QITS_CI_RUNNER_DECOMMISSION=r1",
          "-e",
          "QITS_CI_RUNNER_DECOMMISSION_VOLUME=qits-ci-runner-state-r1",
          "sha256:old");

  /**
   * The runner's own container as {@code docker inspect} answers it, with the state volume mounted
   * at this test's state directory — where {@link Decommission} looks for it.
   */
  private void containerWithStateVolume() throws Exception {
    docker.answerFor(
        "inspect",
        SELF,
        0,
        new JsonArray()
                .add(
                    new JsonObject()
                        .put("Id", SELF + "0".repeat(52))
                        .put("Image", "sha256:old")
                        .put(
                            "Mounts",
                            new JsonArray()
                                .add(
                                    new JsonObject()
                                        .put("Type", "bind")
                                        .put("Source", "/var/run/docker.sock")
                                        .put("Destination", "/var/run/docker.sock"))
                                .add(
                                    new JsonObject()
                                        .put("Type", "volume")
                                        .put("Name", "qits-ci-runner-state-r1")
                                        .put("Destination", state.toString())))
                        .put(
                            "HostConfig",
                            new JsonObject()
                                .put("RestartPolicy", Map.of("Name", "unless-stopped"))))
                .encode()
            + "\n",
        "");
  }

  /** Neither the helper nor a volume removal: what a self-update's retirement must never do. */
  private void assertNoDecommission() throws Exception {
    assertFalse(docker.calls().contains(DECOMMISSIONER_RUN), this::calls);
    assertTrue(
        docker.calls().stream().noneMatch(c -> c.contains("volume") || String.join(" ", c).contains("DECOMMISSION")),
        this::calls);
    assertTrue(Files.exists(state.resolve("client.json")), "a successor dials with this client");
  }

  /** The decommission's docker half, in order: stay down, then the helper. */
  private void assertDecommissioned() throws Exception {
    List<List<String>> calls = docker.calls();
    int stayDown = calls.indexOf(List.of("update", "--restart=no", SELF));
    int helper = calls.indexOf(DECOMMISSIONER_RUN);
    assertTrue(stayDown >= 0, this::calls);
    assertTrue(helper > stayDown, this::calls);
    assertFalse(Files.exists(state.resolve("client.json")), "the revoked client is forgotten");
  }

  @Test
  void aDeletedRetireCancelsHeldRunsStaysDownAndHandsContainerAndVolumeToTheHelper()
      throws Exception {
    containerWithStateVolume();
    docker.answerFor("ps", "label=qits.ci.runner.run=run-a", 0, "stepcontainer1\n", "");
    holdARun();

    host.send(Retire.deleted("deleted by admin"));
    assertEquals(ExitCode.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertDecommissioned();
    List<List<String>> calls = docker.calls();
    assertTrue(
        calls.indexOf(List.of("rm", "-f", "stepcontainer1"))
            < calls.indexOf(List.of("update", "--restart=no", SELF)),
        "the held run's containers go first: " + calls);
    host.send(new Backlog(3));
    Thread.sleep(200);
    assertEquals(1, host.all(Reserve.class).size(), "a deleted runner takes no more work");
    assertEquals(1, host.upgrades.get(), "and never dials again");
  }

  @Test
  void aDeletedRetireMidRolloverStillDecommissionsWhereASupersededOneWouldBeIgnored()
      throws Exception {
    runnerContainer();
    containerWithStateVolume();
    holdARun();
    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    awaitCall(c -> has(c, List.of("pull", IMAGE)));

    host.send(Retire.deleted("deleted by admin"));
    assertEquals(ExitCode.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertDecommissioned();
  }

  @Test
  void theHostClosingRunnerDeletedDecommissionsAtOnce() throws Exception {
    containerWithStateVolume();
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.closeSocket(1008, CiRunnerProtocol.CloseReason.RUNNER_DELETED);
    assertEquals(ExitCode.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertDecommissioned();
    assertEquals(1, host.upgrades.get(), "no redial");
  }

  @Test
  void anyOtherRefusalCloseIsRedialledAsEver() throws Exception {
    containerWithStateVolume();
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    // UNKNOWN_RUNNER is also what a host that cannot read identity at all says: not the runner's fault.
    host.closeSocket(1008, "UNKNOWN_RUNNER");
    host.await(Hello.class, 2);
    assertFalse(exit.isDone());
    assertFalse(has(docker.calls(), List.of("update", "--restart=no", SELF)));
  }

  @Test
  void aTokenEndpointThatKeepsRefusingTheClientIsReadAsDeletedAfterTheStreak() throws Exception {
    refusalConfirmMillis = 400;
    containerWithStateVolume();
    host.tokenStatus = 401;
    host.tokenErrorBody = "{\"error\":\"invalid_client\",\"error_description\":\"client authentication failed\"}";
    start("t", 10_000);

    assertEquals(ExitCode.OK, exit.get(15, TimeUnit.SECONDS));
    runner = null;
    assertDecommissioned();
    assertTrue(host.tokenBodies.size() >= 3, "a streak, not one answer: " + host.tokenBodies.size());
    assertEquals(0, host.upgrades.get());
  }

  @Test
  void aTokenEndpointThatIsDownOrAnEdgeRefusalIsRetriedForever() throws Exception {
    refusalConfirmMillis = 200;
    containerWithStateVolume();
    host.tokenStatus = 502;
    host.tokenErrorBody = "Bad Gateway";
    start("t", 10_000);

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (host.tokenBodies.size() < 6 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    host.tokenStatus = 401;
    host.tokenErrorBody = "{\"error\":\"client authentication is required\"}";
    int before = host.tokenBodies.size();
    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (host.tokenBodies.size() < before + 6 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(host.tokenBodies.size() >= before + 6, "still dialling");
    assertFalse(exit.isDone(), "transient failures never decommission");
    assertFalse(has(docker.calls(), List.of("update", "--restart=no", SELF)));
    assertTrue(Files.exists(state.resolve("client.json")));

    // And the platform coming back is a runner that connects as if nothing happened.
    ackWith(1, 0);
    host.tokenStatus = 200;
    host.await(Hello.class);
    assertFalse(exit.isDone());
  }

  @Test
  void aQuarantinedFrameSetsTheRunnersOwnFlagAndAReinstatedClearsIt() throws Exception {
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);
    assertFalse(runner.quarantined());

    host.send(new Quarantined("3 runner-caused failures in a row", "2026-09-28T12:00:00Z"));
    awaitTrue(runner::quarantined);

    host.send(new Reinstated("admin"));
    awaitTrue(() -> !runner.quarantined());
  }

  @Test
  void aQuarantinedRunnerKeepsReservingTheHostAlreadyAnswersNothing() throws Exception {
    // The runner does not change its own Reserve behaviour on Quarantined — the host is the one
    // that stops answering Take while a runner is quarantined, the same as while it drains.
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(CiRunnerProtocol.CAPABILITY_VERSION, 1));
            h.send(new Backlog(1));
            h.send(new Quarantined("failed health check", "2026-09-28T12:00:00Z"));
          } else if (message instanceof Reserve) {
            h.send(new Nothing());
          }
        };
    start("t", 10_000);
    host.await(Hello.class);
    awaitTrue(runner::quarantined);
    host.await(Reserve.class);
    assertEquals(0, runner.reservations().held());
  }

  @Test
  void theFirstAckRemovesExitedAndLingeringPredecessorsAndNeverItself() throws Exception {
    rolloverSettings = new Rollover.Settings(100, 400, 3_000, 400, 50);
    docker.answerFor(
        "ps",
        "label=qits.ci.runner.process=r1",
        0,
        String.join(
            "\n",
            "0123456789ab|" + CiRunnerBinary.VERSION + "|running",
            "aaaaaaaaaaaa|2026.900.1|exited",
            "bbbbbbbbbbbb|2026.900.2|running",
            "cccccccccccc|2026.900.3|running",
            ""),
        "");
    docker.answerFor("inspect", "bbbbbbbbbbbb", 0, "exited\n", "");
    docker.answerFor("inspect", "cccccccccccc", 0, "running\n", "");
    ackWith(1, 0);
    start("t", 10_000);

    awaitCall(c -> has(c, List.of("rm", "-f", "cccccccccccc")));
    List<List<String>> calls = docker.calls();
    assertTrue(
        calls.contains(
            List.of(
                "ps",
                "-a",
                "--format",
                "{{.ID}}|{{.Label \"qits.ci.runner.version\"}}|{{.State}}",
                "--filter",
                "label=qits.ci.runner.process=r1")),
        this::calls);
    assertTrue(calls.contains(List.of("rm", "-f", "aaaaaaaaaaaa")), "the exited predecessor");
    assertTrue(calls.contains(List.of("rm", "-f", "bbbbbbbbbbbb")), "the one that exited while waited on");
    int waited = calls.indexOf(List.of("inspect", "--format", "{{.State.Status}}", "bbbbbbbbbbbb"));
    assertTrue(waited >= 0 && waited < calls.indexOf(List.of("rm", "-f", "bbbbbbbbbbbb")));
    assertFalse(calls.contains(List.of("rm", "-f", SELF)), "never itself");
    // A reconnect's Ack does not look for predecessors again: once per process.
    host.socket.close();
    host.await(Hello.class, 2);
    Thread.sleep(300);
    assertEquals(
        1,
        docker.calls("ps").stream().filter(c -> c.contains("label=qits.ci.runner.process=r1")).count(),
        this::calls);
  }
}

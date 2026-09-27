package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import eu.wohlben.qits.cirunner.protocol.Reap;
import eu.wohlben.qits.cirunner.protocol.Reaped;
import eu.wohlben.qits.cirunner.protocol.Released;
import eu.wohlben.qits.cirunner.protocol.Reserve;
import eu.wohlben.qits.cirunner.protocol.Take;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import io.vertx.core.Vertx;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
                client -> new ControlSocket.Settings(heartbeatMillis, 50, 200),
                new BootSweep(d, docker.binary, "r1"),
                new Launcher(d, docker.binary, "r1", new BuildPlane(d, docker.binary, "moby/buildkit:v0.33.0")),
                new Reaper(d, docker.binary, "r1"),
                CAPS,
                client -> new Bearer(http, client, System::currentTimeMillis)));
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
    // The sweep ran before the Hello, by the runner's own label only.
    assertEquals(
        List.of(
            List.of("ps", "-aq", "--filter", "label=qits.ci.runner=r1"),
            List.of("rm", "-f", "leftover1")),
        docker.calls());
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
  void aLaunchIsAnsweredLaunchedAndAReapReaped() throws Exception {
    docker.answer("run", 0, "c0ffee\n", "");
    ackWith(1, 0);
    start("t", 10_000);
    host.await(Hello.class);

    host.send(new Launch("run-1", 0, WorkloadSpec.of("alpine:3", "qits-ci-run-1-x-0")));
    assertEquals(new Launched("run-1", 0, "c0ffee"), host.await(Launched.class));
    host.send(new Reap("run-1", 0, "qits-ci-run-1-x-0"));
    assertEquals(new Reaped("run-1", 0), host.await(Reaped.class));

    List<String> run = docker.calls("run").getFirst();
    assertTrue(run.contains("qits.ci.runner=r1"), run::toString);
    assertTrue(
        docker.calls().stream().anyMatch(c -> c.equals(List.of("rm", "-f", "qits-ci-run-1-x-0"))));
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
}

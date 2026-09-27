package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.Ack;
import eu.wohlben.qits.cirunner.protocol.Backlog;
import eu.wohlben.qits.cirunner.protocol.Cancel;
import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.cirunner.protocol.CiRunnerBinary;
import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol;
import eu.wohlben.qits.cirunner.protocol.Hello;
import eu.wohlben.qits.cirunner.protocol.Launch;
import eu.wohlben.qits.cirunner.protocol.Nothing;
import eu.wohlben.qits.cirunner.protocol.Reap;
import eu.wohlben.qits.cirunner.protocol.Released;
import eu.wohlben.qits.cirunner.protocol.Reserve;
import eu.wohlben.qits.cirunner.protocol.Take;
import io.vertx.core.Vertx;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jboss.logging.Logger;

/**
 * The runner's whole life:
 *
 * <pre>
 *   register (once) → [ sweep → dial → Hello → Ack{slots} → Backlog / Reserve / Take / Launch /
 *   Reap / Cancel / Released … → close ] forever
 * </pre>
 *
 * <p><b>qits-ci keeps the run; the runner keeps the containers.</b> Nothing here knows what a step
 * is for, how many a run has or whether one passed — qits-ci drives each run and the step's own
 * daemon talks to qits-ci directly. The runner reserves, starts what it is told to start, removes
 * what it is told to remove, and counts slots.
 *
 * <p>A plain class with a plain constructor, like qits-ci-daemon's {@code DaemonMain}: {@link Main}
 * resolves configuration, and the suite drives this against a real in-JVM server and a shell script
 * standing in for docker.
 */
public final class RunnerMain implements ControlSocket.Listener {

  private static final Logger LOG = Logger.getLogger(RunnerMain.class);

  /** The collaborators {@link Main} builds; a record so the suite can build them too. */
  public record Parts(
      Registration registration,
      java.util.function.Function<ClientCredentials, ControlSocket.Settings> settings,
      BootSweep sweep,
      Launcher launcher,
      Reaper reaper,
      Capabilities capabilities,
      java.util.function.Function<ClientCredentials, Bearer> bearer) {}

  private final Vertx vertx;
  private final RunnerEnv env;
  private final Parts parts;
  private final Reservations reservations = new Reservations();

  /**
   * Docker calls block, and frames arrive on an event loop, so every docker-touching answer runs
   * here. Unbounded on purpose: the host never has more launches in flight than slots it granted.
   */
  private final ExecutorService workers =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "ci-runner-worker");
            thread.setDaemon(true);
            return thread;
          });

  private final CompletableFuture<Integer> exit = new CompletableFuture<>();
  private volatile ControlSocket socket;

  public RunnerMain(Vertx vertx, RunnerEnv env, Parts parts) {
    this.vertx = vertx;
    this.env = env;
    this.parts = parts;
  }

  /**
   * Register if needed, then run until something fatal ends the process. Blocks the calling thread
   * — the application's main thread, which has nothing else to do.
   */
  public int run() {
    ClientCredentials client;
    try {
      client = parts.registration().ensure(env, parts.capabilities());
    } catch (Registration.Failed failed) {
      LOG.errorf("ci-runner cannot start: %s. Exiting.", failed.getMessage());
      return failed.exitCode();
    }
    Bearer bearer = parts.bearer().apply(client);
    socket =
        new ControlSocket(
            vertx, client.socketUrl(), bearer::token, parts.settings().apply(client), this);
    socket.start();
    try {
      return exit.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ExitCode.OK;
    } catch (Exception e) {
      LOG.error("ci-runner ended abnormally", e);
      return ExitCode.STATE_UNUSABLE;
    } finally {
      ControlSocket s = socket;
      if (s != null) {
        s.stop();
      }
      workers.shutdownNow();
    }
  }

  /** End the run loop with {@code code}; the suite's way out, and an orderly shutdown's. */
  public void stop(int code) {
    exit.complete(code);
  }

  @Override
  public void onConnected() {
    // Every session starts clean — BootSweep's javadoc argues why — and the sweep is docker work,
    // so it runs off the loop and the Hello waits for it: the host must never Launch into a session
    // whose leftovers are still being removed under the same labels.
    workers.execute(
        () -> {
          parts.sweep().sweep();
          send(
              new Hello(
                  CiRunnerBinary.VERSION,
                  CiRunnerProtocol.CAPABILITY_VERSION,
                  env.slots(),
                  parts.capabilities()));
        });
  }

  @Override
  public void onMessage(CiRunnerMessage message) {
    switch (message) {
      case Ack ack -> onAck(ack);
      case Backlog backlog -> reserveIf(reservations.onBacklog(backlog.queued()));
      case Take take -> {
        LOG.infof(
            "ci-runner took run %s (%s@%s %s)", take.runId(), take.repoName(), take.branch(),
            take.sha());
        reserveIf(reservations.onTake(take.runId()));
      }
      case Nothing _ -> reservations.onNothing();
      case Launch launch -> workers.execute(() -> send(parts.launcher().launch(launch)));
      case Reap reap -> workers.execute(() -> send(parts.reaper().reap(reap)));
      case Cancel cancel -> workers.execute(() -> parts.reaper().cancel(cancel.runId()));
      case Released released -> {
        LOG.infof("ci-runner released run %s", released.runId());
        reserveIf(reservations.onReleased(released.runId()));
      }
      default ->
          // Everything else in the sealed set is runner→host; a host echoing one is not a
          // conversation this version has.
          LOG.debugf("ci-runner ignored a %s from the host", message.getClass().getSimpleName());
    }
  }

  private void onAck(Ack ack) {
    if (ack.capabilityVersion() != CiRunnerProtocol.CAPABILITY_VERSION) {
      LOG.errorf(
          "ci-runner speaks capability version %d, the host answered %d — exiting rather than"
              + " guessing.",
          CiRunnerProtocol.CAPABILITY_VERSION, ack.capabilityVersion());
      exit.complete(ExitCode.CAPABILITY_MISMATCH);
      return;
    }
    LOG.infof("ci-runner connected slots=%d", ack.slots());
    reserveIf(reservations.onAck(ack.slots()));
  }

  @Override
  public void onClosed() {
    // The host fails every run this runner held; the next session's sweep removes their
    // containers. Nothing is carried across.
    reservations.reset();
  }

  private void reserveIf(boolean reserve) {
    if (reserve) {
      send(new Reserve());
    }
  }

  private void send(CiRunnerMessage message) {
    ControlSocket s = socket;
    if (s == null) {
      return;
    }
    s.send(message)
        .onFailure(
            t ->
                LOG.warnf(
                    "ci-runner could not send %s: %s",
                    message.getClass().getSimpleName(), t.getMessage()));
  }

  /** For the suite: the slot state. */
  Reservations reservations() {
    return reservations;
  }
}

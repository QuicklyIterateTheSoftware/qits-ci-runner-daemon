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
import eu.wohlben.qits.cirunner.protocol.Quarantined;
import eu.wohlben.qits.cirunner.protocol.Reap;
import eu.wohlben.qits.cirunner.protocol.Reinstated;
import eu.wohlben.qits.cirunner.protocol.Released;
import eu.wohlben.qits.cirunner.protocol.Reserve;
import eu.wohlben.qits.cirunner.protocol.Retire;
import eu.wohlben.qits.cirunner.protocol.Take;
import eu.wohlben.qits.cirunner.protocol.Upgrade;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jboss.logging.Logger;

/**
 * The runner's whole life:
 *
 * <pre>
 *   register (once) → [ sweep → dial → Hello → Ack{slots} → Backlog / Reserve / Take / Launch /
 *   Reap / Cancel / Released … → close ] until Retire
 * </pre>
 *
 * <p>An {@code Upgrade} makes the process drain and hands the rest to {@link Rollover}: a successor
 * container is started once no run is held, and a {@code Retire} is the one orderly way out.
 *
 * <p><b>A deleted runner decommissions itself</b> ({@link #decommission}): told so by a {@code
 * Retire} of kind {@code DELETED} while connected, or by {@link ControlSocket.Listener#onDeleted}
 * when it finds out on a dial. It takes no more work, cancels what it holds, sweeps its step
 * containers, and hands its container and state volume to {@link Decommission}.
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
      java.util.function.Function<ClientCredentials, Bearer> bearer,
      Rollover.Factory rollover,
      Telemetry telemetry,
      Decommission decommission,
      Housekeeping housekeeping) {

    /** The parts of a runner that collects none of its disk's garbage. */
    public Parts(
        Registration registration,
        java.util.function.Function<ClientCredentials, ControlSocket.Settings> settings,
        BootSweep sweep,
        Launcher launcher,
        Reaper reaper,
        Capabilities capabilities,
        java.util.function.Function<ClientCredentials, Bearer> bearer,
        Rollover.Factory rollover,
        Telemetry telemetry,
        Decommission decommission) {
      this(
          registration, settings, sweep, launcher, reaper, capabilities, bearer, rollover,
          telemetry, decommission, null);
    }

    /**
     * The parts of a runner with no way to remove itself: told it was deleted, it still stops for
     * good and exits, and leaves its container to a person.
     */
    public Parts(
        Registration registration,
        java.util.function.Function<ClientCredentials, ControlSocket.Settings> settings,
        BootSweep sweep,
        Launcher launcher,
        Reaper reaper,
        Capabilities capabilities,
        java.util.function.Function<ClientCredentials, Bearer> bearer,
        Rollover.Factory rollover,
        Telemetry telemetry) {
      this(
          registration, settings, sweep, launcher, reaper, capabilities, bearer, rollover,
          telemetry, null, null);
    }

    /** The parts of a runner that ships its log nowhere. */
    public Parts(
        Registration registration,
        java.util.function.Function<ClientCredentials, ControlSocket.Settings> settings,
        BootSweep sweep,
        Launcher launcher,
        Reaper reaper,
        Capabilities capabilities,
        java.util.function.Function<ClientCredentials, Bearer> bearer,
        Rollover.Factory rollover) {
      this(
          registration, settings, sweep, launcher, reaper, capabilities, bearer, rollover,
          Telemetry.off(), null, null);
    }
  }

  /** How long an exiting runner waits for its last log lines to be shipped. */
  static final long TELEMETRY_LAST_WORDS_MILLIS = 3_000;

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
  private volatile Rollover rollover;

  /** Set once, by the first of the ways a runner learns it was deleted. */
  private final java.util.concurrent.atomic.AtomicBoolean decommissioning =
      new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * For this runner's own status only. The host already answers every {@link Reserve} with {@link
   * Nothing} while a runner is quarantined, so nothing here needs to branch on it — {@link
   * Quarantined} and {@link Reinstated} exist to tell the person at the machine, via the log, why the
   * runner sits idle (or no longer does).
   */
  private volatile boolean quarantined;

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
    // The log export needs the bearer, so it begins here; what was logged before waited for it.
    parts.telemetry().start(bearer::token);
    rollover = parts.rollover().create(client, reservations::held);
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
      Rollover r = rollover;
      if (r != null) {
        r.shutdown();
      }
      if (parts.housekeeping() != null) {
        parts.housekeeping().shutdown();
      }
      workers.shutdownNow();
      parts.telemetry().stop(TELEMETRY_LAST_WORDS_MILLIS);
    }
  }

  /** End the run loop with {@code code}; the suite's way out, and an orderly shutdown's. */
  public void stop(int code) {
    exit.complete(code);
  }

  @Override
  public void onConnected() {
    // Every session starts without leftovers — BootSweep's javadoc argues why, and why the runs a
    // lost socket left carried keep their containers — and the sweep is docker work, so it runs off
    // the loop and the Hello waits for it: the host must never Launch into a session whose leftovers
    // are still being removed under the same labels. The Hello claims what was carried.
    workers.execute(
        () -> {
          List<String> carried = reservations.carried();
          parts.sweep().sweep(carried);
          send(
              new Hello(
                  CiRunnerBinary.VERSION,
                  CiRunnerProtocol.CAPABILITY_VERSION,
                  env.slots(),
                  parts.capabilities(),
                  carried));
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
        rollover.poke();
      }
      case Upgrade upgrade when !env.selfUpdate() ->
          // Deployer-managed: the deployer replaces this container, so there is nothing to drain
          // for and no successor to start. The frame has no refusal to answer with; the capability
          // label this runner advertises is how the host knows.
          LOG.infof(
              "ci-runner ignored an upgrade to %s: it is deployer-managed (%s=false), and its"
                  + " deployer, not the runner, replaces it",
              upgrade.version(), RunnerEnv.SELF_UPDATE);
      case Upgrade upgrade -> {
        // At once, on the loop: not one more Reserve goes out after this frame.
        reservations.drain();
        rollover.upgrade(upgrade);
      }
      case Retire retire when retire.kind() == Retire.Kind.DELETED ->
          decommission("the host deleted this runner (" + retire.reason() + ")");
      case Retire retire -> {
        if (rollover.retire(retire)) {
          workers.execute(this::leave);
        }
      }
      case Quarantined quarantine -> {
        quarantined = true;
        LOG.warnf(
            "ci-runner is quarantined since %s: %s; it takes no new runs until reinstated",
            quarantine.since(), quarantine.reason());
      }
      case Reinstated reinstated -> {
        quarantined = false;
        LOG.infof("ci-runner reinstated by %s", reinstated.by());
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
    // Admitted: healthy from now, rather than one heartbeat interval from now.
    beat();
    parts.launcher().onAck(ack.registryMirrors());
    // Before the slots are counted: a carried run the host did not keep holds no slot any more.
    for (String runId : reservations.settleCarried(ack.adoptedRuns())) {
      LOG.infof("ci-runner's host did not keep carried run %s; cancelling it", runId);
      workers.execute(() -> parts.reaper().cancel(runId));
    }
    reserveIf(reservations.onAck(ack.slots()));
    // The first Ack is this version proven on the wire, so whatever this runner ran before it is
    // done with. Once per process (Rollover keeps the latch), and off the loop: it can wait a minute.
    // Housekeeping starts after it, so the leftover-image sweep finds the predecessors gone; it too
    // is once per process, and runs on its own thread from there.
    workers.execute(
        () -> {
          try {
            rollover.removePredecessors();
          } finally {
            if (parts.housekeeping() != null && !decommissioning.get()) {
              parts.housekeeping().start(reservations::held);
            }
          }
        });
  }

  /**
   * The one orderly exit: no restart for this container, then the socket closed for good — no sweep
   * and no redial on the way out, because the containers under this runner's label are the
   * successor's now — then exit 0.
   */
  private void leave() {
    rollover.leave();
    ControlSocket s = socket;
    if (s != null) {
      s.stop();
    }
    LOG.info("ci-runner exits: retired");
    exit.complete(ExitCode.OK);
  }

  @Override
  public void onHeartbeatSent() {
    beat();
  }

  /**
   * Touch the heartbeat file {@link HealthCommand} reads. Never fatal: a runner that cannot write
   * its state directory says so on its next registration or rollover, not here, every 10 s.
   */
  private void beat() {
    try {
      HealthCommand.touch(env.stateDir());
    } catch (java.io.IOException | RuntimeException e) {
      LOG.debugf("ci-runner could not touch its heartbeat file: %s", e.getMessage());
    }
  }

  @Override
  public void onDeleted(String why) {
    decommission(why);
  }

  /**
   * This runner was deleted. On the loop, at once: not one more {@code Reserve}, and no redial —
   * the host is about to close the socket, and a dial could only mint on a revoked client. Then,
   * off the loop: every held run's containers removed (the host fails a deleted runner's runs on
   * its side; its containers are this side's), a sweep for anything else under the runner's label,
   * {@link Decommission#leave}, and exit 0.
   */
  private void decommission(String why) {
    if (!decommissioning.compareAndSet(false, true)) {
      return;
    }
    LOG.warnf("ci-runner decommissions itself: %s", why);
    reservations.drain();
    List<String> held = reservations.heldRuns();
    ControlSocket s = socket;
    if (s != null) {
      s.stop();
    }
    Rollover r = rollover;
    if (r != null) {
      r.shutdown();
    }
    if (parts.housekeeping() != null) {
      parts.housekeeping().shutdown();
    }
    workers.execute(
        () -> {
          try {
            for (String runId : held) {
              parts.reaper().cancel(runId);
            }
            parts.sweep().sweep();
            if (parts.decommission() != null) {
              parts.decommission().leave();
            } else if (r != null) {
              r.leave();
            }
          } catch (RuntimeException e) {
            LOG.warnf("ci-runner's decommission failed part way: %s", e.getMessage());
          } finally {
            LOG.info("ci-runner exits: decommissioned");
            exit.complete(ExitCode.OK);
          }
        });
  }

  @Override
  public void onClosed() {
    // The held runs are carried to the next session, whose Hello claims them and whose first Ack
    // says which the host kept: it waits a short grace for this runner before failing them, so a
    // blip no longer costs a running step.
    reservations.suspend();
    Rollover r = rollover;
    if (r != null) {
      r.poke();
    }
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

  /** For the suite: whether the host's last word on quarantine was {@link Quarantined}. */
  boolean quarantined() {
    return quarantined;
  }
}

package eu.wohlben.qits.cirunner;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The slot arithmetic, and the one rule it enforces: <b>send {@code Reserve} when a slot is free,
 * the backlog says there is work, and no {@code Reserve} is outstanding</b>.
 *
 * <p>Every method answers "send a Reserve now?", and a {@code true} has already counted the request
 * as outstanding — so the caller sends exactly what it was told to and never decides anything
 * itself. That is what makes the rule testable without a socket.
 *
 * <ul>
 *   <li>{@code Nothing} parks the runner until the next {@code Backlog} or {@code Released}: the
 *       host just said there is nothing this runner can take, and asking again at once would spin
 *       on a queue it cannot serve (a {@code docker: true} run on a socketless runner, say).
 *   <li>{@code Take} decrements the remembered backlog, because the host's next push says the same
 *       thing a round trip later; without it a runner with two free slots and a backlog of one
 *       would reserve twice and collect a guaranteed {@code Nothing}.
 *   <li>{@code Released} is the only thing that frees a slot. The runner cannot infer a run's end
 *       from its frames — see the protocol's {@code Released}.
 *   <li>{@code Upgrade} drains: from then on nothing is reserved, for the rest of the process's life
 *       and across reconnects — a process that is being replaced never takes work again. The runs
 *       it holds finish; see {@link Rollover}.
 * </ul>
 *
 * <p>Synchronized, because frames arrive on the event loop and launches answer from workers.
 */
public final class Reservations {

  private int slots;
  private int backlog;
  private boolean outstanding;
  private boolean parked;
  private boolean draining;
  private final Set<String> held = new LinkedHashSet<>();

  /** A new session: the host's cap, and nothing held — see {@link BootSweep} for why nothing. */
  public synchronized boolean onAck(int slots) {
    this.slots = Math.max(0, slots);
    return decide();
  }

  public synchronized boolean onBacklog(int queued) {
    backlog = Math.max(0, queued);
    parked = false;
    return decide();
  }

  public synchronized boolean onTake(String runId) {
    outstanding = false;
    held.add(runId);
    backlog = Math.max(0, backlog - 1);
    return decide();
  }

  public synchronized boolean onNothing() {
    outstanding = false;
    parked = true;
    return false;
  }

  public synchronized boolean onReleased(String runId) {
    held.remove(runId);
    parked = false;
    return decide();
  }

  /** Never reserve again. Held runs are kept: they finish, and their {@code Released} still counts. */
  public synchronized void drain() {
    draining = true;
  }

  public synchronized boolean draining() {
    return draining;
  }

  /**
   * The socket closed: every held run is the host's to fail, and nothing is outstanding. Draining
   * survives it — a reconnect does not un-ask the upgrade.
   */
  public synchronized void reset() {
    slots = 0;
    backlog = 0;
    outstanding = false;
    parked = false;
    held.clear();
  }

  public synchronized int held() {
    return held.size();
  }

  public synchronized boolean holds(String runId) {
    return held.contains(runId);
  }

  private boolean decide() {
    if (draining || outstanding || parked || held.size() >= slots || backlog <= 0) {
      return false;
    }
    outstanding = true;
    return true;
  }
}

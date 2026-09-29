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

  /**
   * The runs held when the socket dropped, until the next session's first {@code Ack} says which of
   * them the host kept ({@link #settleCarried}); empty on a first connection and once settled.
   */
  private final Set<String> carried = new LinkedHashSet<>();

  /** The host's cap. Held runs are kept: a re-sent {@code Ack} is only a new number. */
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
    carried.remove(runId);
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
   * The socket closed: no slots and nothing outstanding until the next {@code Ack}, and every held
   * run is <b>carried</b> — kept, containers and all, for the host to adopt on the next connection
   * (see {@link BootSweep}). Draining survives it too — a reconnect does not un-ask the upgrade.
   */
  public synchronized void suspend() {
    slots = 0;
    backlog = 0;
    outstanding = false;
    parked = false;
    carried.addAll(held);
  }

  /** The runs to claim in the next {@code Hello}, in the order they were taken — a copy. */
  public synchronized java.util.List<String> carried() {
    return java.util.List.copyOf(carried);
  }

  /**
   * The host answered the claim: keep the carried runs it {@code adopted} and forget the rest,
   * which are returned for their containers to be removed. {@code null} — a host that never read
   * the claim — adopts nothing. Only the first {@code Ack} of a session settles; after it there is
   * nothing carried and this returns nothing.
   */
  public synchronized java.util.List<String> settleCarried(java.util.List<String> adopted) {
    java.util.List<String> dropped = new java.util.ArrayList<>();
    for (String runId : carried) {
      if (adopted == null || !adopted.contains(runId)) {
        held.remove(runId);
        dropped.add(runId);
      }
    }
    carried.clear();
    return dropped;
  }

  public synchronized int held() {
    return held.size();
  }

  /** The runs held now, in the order they were taken — a copy. */
  public synchronized java.util.List<String> heldRuns() {
    return java.util.List.copyOf(held);
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

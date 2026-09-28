package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The reserve gate: a free slot, a positive backlog, and nothing outstanding — all three. */
class ReservationsTest {

  @Test
  void noReserveBeforeTheBacklogSaysThereIsWork() {
    Reservations r = new Reservations();
    assertFalse(r.onAck(2), "an empty backlog is no reason to ask");
    assertTrue(r.onBacklog(1));
  }

  @Test
  void onlyOneReserveIsOutstandingAtATime() {
    Reservations r = new Reservations();
    r.onAck(3);
    assertTrue(r.onBacklog(5));
    assertFalse(r.onBacklog(6), "a second Reserve while the first is unanswered");
  }

  @Test
  void aTakeAsksAgainWhileSlotsAndBacklogRemain() {
    Reservations r = new Reservations();
    r.onAck(2);
    assertTrue(r.onBacklog(3));
    assertTrue(r.onTake("a"), "one held of two, backlog still positive");
    assertFalse(r.onTake("b"), "both slots held");
    assertEquals(2, r.held());
  }

  @Test
  void aTakeCountsDownTheRememberedBacklogSoTheLastRunIsNotReservedTwice() {
    Reservations r = new Reservations();
    r.onAck(2);
    assertTrue(r.onBacklog(1));
    assertFalse(r.onTake("a"), "the one queued run is the one just taken");
  }

  @Test
  void nothingParksTheRunnerUntilTheNextBacklog() {
    Reservations r = new Reservations();
    r.onAck(1);
    assertTrue(r.onBacklog(2));
    assertFalse(r.onNothing());
    assertFalse(r.onBacklog(0), "a backlog that emptied is still no reason");
  }

  @Test
  void aParkedRunnerAsksAgainOnTheNextBacklogPush() {
    Reservations r = new Reservations();
    r.onAck(1);
    assertTrue(r.onBacklog(2));
    r.onNothing();
    assertTrue(r.onBacklog(2), "the host pushed again; the queue may hold something new");
  }

  @Test
  void aReleasedRunFreesItsSlotAndAsksAgainWhenThereIsWork() {
    Reservations r = new Reservations();
    r.onAck(1);
    assertTrue(r.onBacklog(3));
    assertFalse(r.onTake("a"), "the one slot is held");
    assertTrue(r.onReleased("a"));
    assertFalse(r.holds("a"));
  }

  @Test
  void theHostsSlotCountIsTheCap() {
    Reservations r = new Reservations();
    r.onAck(0);
    assertFalse(r.onBacklog(10), "an Ack granting no slots grants no runs");
  }

  @Test
  void aClosedSessionForgetsEverythingItHeld() {
    Reservations r = new Reservations();
    r.onAck(2);
    r.onBacklog(2);
    r.onTake("a");
    r.reset();
    assertEquals(0, r.held());
    assertFalse(r.onBacklog(5), "no Ack yet in the new session, so no slots");
    assertTrue(r.onAck(2), "the new session's Ack, with the backlog already known");
  }

  @Test
  void aDrainingRunnerNeverReservesAgainAndAReconnectDoesNotUndoIt() {
    Reservations r = new Reservations();
    r.onAck(2);
    assertTrue(r.onBacklog(3));
    r.onTake("a");
    r.drain();
    assertFalse(r.onBacklog(3), "no Reserve after the Upgrade");
    assertFalse(r.onReleased("a"), "a freed slot is not refilled");
    assertEquals(0, r.held());
    r.reset();
    assertFalse(r.onAck(2));
    assertFalse(r.onBacklog(9));
    assertTrue(r.draining());
  }
}

package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci → runner: "this connection's runner is done; go." Two occasions, told apart by {@link
 * #kind()}:
 *
 * <ul>
 *   <li>{@link Kind#SUPERSEDED} — a runner of the pinned version has taken over for you. Sent to a
 *       draining connection (see {@link Upgrade}) once a connection of the pinned version for the
 *       same runner has completed its {@link Hello}. The receiving process closes its socket and
 *       exits 0 — and does not sweep or redial on the way out, because the containers under its
 *       label, and the state volume it shares with its successor, now belong to that successor.
 *   <li>{@link Kind#DELETED} — an operator deleted this runner. There is no successor: the runner's
 *       idp client is revoked right after this frame, so nothing of it will ever dial again. The
 *       receiving process takes no more work, removes what it runs, and <b>decommissions</b> itself —
 *       its own container and its state volume go, because the credentials in that volume are dead.
 * </ul>
 *
 * <p><b>THE WIRE SHAPE IS FROZEN</b> for {@link Upgrade}'s reason: the type {@code "retire"} and the
 * string field {@code reason}. Fields may be added; this one may not be renamed, retyped or removed.
 *
 * <p><b>{@code kind} is such an added field</b>, and it is only on the wire when it is {@link
 * Kind#DELETED}: a {@code SUPERSEDED} retirement encodes exactly as every {@code Retire} before the
 * field existed, and a frame without it — or with a value this binary does not know — decodes as
 * {@code SUPERSEDED}. That is the safe reading in both directions: the worst an unknown kind can do
 * is leave a volume behind, never delete one a successor needs. An older runner, which reads only
 * {@code reason}, treats a {@code DELETED} retirement as an operator's retirement — it takes its own
 * restart policy away and exits 0, so its container stops for good but is not removed.
 *
 * @param reason for the log only — nothing branches on it.
 * @param kind what the retirement is — see above; never null ({@code null} is read as {@link
 *     Kind#SUPERSEDED}).
 */
public record Retire(String reason, Kind kind) implements CiRunnerMessage {

  /** What a retirement is. The wire spells each by its {@link #name()}. */
  public enum Kind {
    /** A successor of the pinned version took over; the state volume is the successor's now. */
    SUPERSEDED,
    /** The runner was deleted; its container and its state volume go. */
    DELETED;

    /** The wire value read back: an absent or unknown one is {@link #SUPERSEDED}. */
    public static Kind fromWire(String value) {
      if (value != null) {
        for (Kind kind : values()) {
          if (kind.name().equals(value)) {
            return kind;
          }
        }
      }
      return SUPERSEDED;
    }
  }

  public Retire {
    if (kind == null) {
      kind = Kind.SUPERSEDED;
    }
  }

  /** A {@link Kind#SUPERSEDED} retirement — the self-update's, and every {@code Retire} before kinds. */
  public Retire(String reason) {
    this(reason, Kind.SUPERSEDED);
  }

  /** A {@link Kind#DELETED} retirement. */
  public static Retire deleted(String reason) {
    return new Retire(reason, Kind.DELETED);
  }
}

package eu.wohlben.qits.cirunner.protocol;

/**
 * A frame {@link CiRunnerCodec#decode} could not turn into a message, with a {@link Reason} a
 * receiver can branch on — above all {@link Reason#UNKNOWN_TYPE}, which is what a peer one
 * capability version ahead looks like and is logged differently from garbage.
 *
 * <p>An {@link IllegalArgumentException} so a caller that only wants "drop the frame" can keep
 * catching what it already catches.
 */
public final class CiRunnerDecodeException extends IllegalArgumentException {

  /** Why a frame did not decode. */
  public enum Reason {
    /** The map carries no {@code type} at all. */
    MISSING_TYPE,
    /** The {@code type} names no message this version knows. */
    UNKNOWN_TYPE,
    /** A known type whose fields are the wrong shape — a string where an object belongs. */
    MALFORMED
  }

  private final Reason reason;
  private final String type;

  public CiRunnerDecodeException(Reason reason, String type, String message) {
    super(message);
    this.reason = reason;
    this.type = type;
  }

  public Reason reason() {
    return reason;
  }

  /** The {@code type} the frame carried, or null when it carried none. */
  public String type() {
    return type;
  }
}

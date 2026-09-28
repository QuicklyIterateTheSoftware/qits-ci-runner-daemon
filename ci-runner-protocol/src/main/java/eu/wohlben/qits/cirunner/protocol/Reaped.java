package eu.wohlben.qits.cirunner.protocol;

/**
 * Runner → qits-ci: a {@link Reap} was carried out. Sent whatever {@code docker rm -f} answered —
 * a container that was already gone is reaped as far as anyone can tell, and a removal that failed
 * is the runner's boot sweep's to retry, not a reason for the host to hold a run open.
 *
 * <p><b>{@code logTail} is the step container's last words</b>, read with {@code docker logs}
 * before the removal destroys them. A step's output normally reaches qits-ci through the step's own
 * daemon; a step whose daemon never dialled back has no other record, and on a runner off the
 * platform's host nobody can read the container once it is gone. So the runner sends the tail of
 * both streams, merged, bounded and with anything bearer-shaped redacted, led by a {@code
 * [container exited <code>]} line when the container had exited. Null when docker could not say —
 * a reap never fails on it — and from a runner older than the field. Adding it is not a capability
 * bump: an older host ignores the field, an older runner never sends it.
 *
 * @param logTail the container's output tail, or null when there is none to offer
 */
public record Reaped(String runId, int stepIndex, String logTail) implements CiRunnerMessage {

  /** A reap with no output to offer — what every runner older than {@code logTail} sends. */
  public Reaped(String runId, int stepIndex) {
    this(runId, stepIndex, null);
  }
}

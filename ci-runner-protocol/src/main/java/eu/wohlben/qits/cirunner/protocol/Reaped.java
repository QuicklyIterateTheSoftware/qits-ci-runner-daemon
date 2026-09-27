package eu.wohlben.qits.cirunner.protocol;

/**
 * Runner → qits-ci: a {@link Reap} was carried out. Sent whatever {@code docker rm -f} answered —
 * a container that was already gone is reaped as far as anyone can tell, and a removal that failed
 * is the runner's boot sweep's to retry, not a reason for the host to hold a run open.
 */
public record Reaped(String runId, int stepIndex) implements CiRunnerMessage {}

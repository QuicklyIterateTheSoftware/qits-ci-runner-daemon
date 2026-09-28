package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci → runner: "you get no work until further notice." Sent when the host has stopped giving
 * this runner work — its slots forced to 0 on the host's side — because of repeated runner-caused
 * failures (an image pull or start the runner could not do, a step daemon that never connects, a
 * lost connection) or because its periodic health check failed. See {@link Reinstated} for the way
 * out.
 *
 * <p>The runner does not need to change its {@link Reserve} behaviour on this frame: the host
 * already answers every one with {@link Nothing} while a runner is quarantined, the same as while it
 * is draining for an {@link Upgrade}. What this frame is for is telling the person at the machine —
 * the runner logs it and keeps its own {@code quarantined} flag for its status — since nothing else
 * on the runner would otherwise say why it sits idle.
 *
 * <p>Additive: an older runner's codec has no arm for {@code "quarantined"} and drops it as an
 * unknown type, staying connected and simply not knowing why it gets no work.
 * {@link CiRunnerProtocol#CAPABILITY_VERSION} does not move for it.
 *
 * @param reason for the log only — nothing branches on it.
 * @param since an ISO-8601 instant: when the quarantine began.
 */
public record Quarantined(String reason, String since) implements CiRunnerMessage {}

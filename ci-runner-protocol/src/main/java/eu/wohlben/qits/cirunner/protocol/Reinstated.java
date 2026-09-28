package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci → runner: "the quarantine is lifted." Sent when a {@link Quarantined} runner is
 * greenlit again — either a person pressed the button in the Runners page, or a health check the
 * host retried while quarantined passed. The host resumes handing the runner work on its own next
 * {@link Reserve}; this frame is for the log and the runner's own {@code quarantined} flag, the same
 * way {@link Quarantined} is.
 *
 * <p>Additive, like {@link Quarantined}: an older runner drops it as an unknown type and stays
 * connected. {@link CiRunnerProtocol#CAPABILITY_VERSION} does not move for it.
 *
 * @param by {@code "admin"} or {@code "healthcheck"} — for the log only, nothing branches on it.
 */
public record Reinstated(String by) implements CiRunnerMessage {}

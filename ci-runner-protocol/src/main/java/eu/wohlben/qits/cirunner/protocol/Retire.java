package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci → runner: "a runner of the pinned version has taken over for you; go." Sent to a draining
 * connection (see {@link Upgrade}) once a connection of the pinned version for the same runner has
 * completed its {@link Hello}. The receiving process closes its socket and exits 0 — and does not
 * sweep or redial on the way out, because the containers under its label now belong to its
 * successor.
 *
 * <p><b>THE WIRE SHAPE IS FROZEN</b> for {@link Upgrade}'s reason: the type {@code "retire"} and the
 * string field {@code reason}. Fields may be added; this one may not be renamed, retyped or removed.
 *
 * @param reason for the journal only — nothing branches on it.
 */
public record Retire(String reason) implements CiRunnerMessage {}

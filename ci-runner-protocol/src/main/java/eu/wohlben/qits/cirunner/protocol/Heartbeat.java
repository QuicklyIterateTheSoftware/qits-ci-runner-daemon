package eu.wohlben.qits.cirunner.protocol;

/**
 * A periodic liveness ping from the runner, every 10s for as long as the socket is open, so the
 * host can tell an idle runner from a wedged one. No fields: the connection identifies the runner.
 */
public record Heartbeat() implements CiRunnerMessage {}

package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci → runner: how many runs are {@code QUEUED}, pushed whenever that number changes and once
 * after {@link Ack}. It is what lets a runner learn of work without polling, and it is a hint rather
 * than a promise: a positive backlog is a reason to {@link Reserve}, never a guarantee the answer is
 * a {@link Take} — local workers and other runners compete for the same rows.
 */
public record Backlog(int queued) implements CiRunnerMessage {}

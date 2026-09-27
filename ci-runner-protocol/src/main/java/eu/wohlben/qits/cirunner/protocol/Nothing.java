package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci → runner: the answer to {@link Reserve} when there was nothing this runner could claim —
 * the queue emptied, somebody else won the compare-and-swap, or what is queued needs a capability
 * this runner did not advertise. The runner waits for the next {@link Backlog} (or a {@link
 * Released}) before asking again, which is what keeps a runner from spinning on a queue it cannot
 * serve.
 */
public record Nothing() implements CiRunnerMessage {}

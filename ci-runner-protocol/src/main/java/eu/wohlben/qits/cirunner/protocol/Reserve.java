package eu.wohlben.qits.cirunner.protocol;

/**
 * Runner → qits-ci: "I have a free slot; claim me a run." The host runs the same claim pass its own
 * workers run, narrowed to runs this runner can take, and answers with exactly one {@link Take} or
 * {@link Nothing}. A runner has at most one {@code Reserve} outstanding, so the answer needs no
 * correlation id.
 */
public record Reserve() implements CiRunnerMessage {}

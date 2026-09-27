package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci → runner: remove every container of this run, now. It frees no slot by itself — the run
 * still ends with {@link Released}, which is the one frame that says a run is over, so the slot
 * arithmetic has one rule rather than two.
 */
public record Cancel(String runId) implements CiRunnerMessage {}

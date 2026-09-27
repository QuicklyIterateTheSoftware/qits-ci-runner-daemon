package eu.wohlben.qits.cirunner.protocol;

/**
 * Runner → qits-ci: {@code docker run} answered with a container id. That is all it proves — the
 * container exists and was started — which is the same thing qits-containers' ensure answered.
 * Whether the step's daemon ever dials is qits-ci's register timeout to judge.
 */
public record Launched(String runId, int stepIndex, String containerId) implements CiRunnerMessage {}

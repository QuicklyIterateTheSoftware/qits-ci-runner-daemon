package eu.wohlben.qits.cirunner.protocol;

/**
 * Runner → qits-ci: the step's container could not be started — a refused spec, an image the host
 * could not pull, docker answering nonzero or not answering within its deadline. {@code detail} is
 * docker's own stderr (or the runner's refusal), bounded at the source, for the human reading the
 * run; nothing parses it.
 */
public record LaunchFailed(String runId, int stepIndex, String detail) implements CiRunnerMessage {}

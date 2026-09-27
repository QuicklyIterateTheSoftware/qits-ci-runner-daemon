package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci's answer to {@link Hello}: the host's own {@link CiRunnerProtocol#CAPABILITY_VERSION} and
 * the number of runs this runner may hold at once.
 *
 * <p><b>{@code slots} is the host's number and it wins.</b> The runner advertises what its operator
 * configured, but the runner row in qits-ci is what an admin edits, so the cap arrives here and the
 * runner never reserves past it. A runner that reads a version it does not know exits rather than
 * guessing, as the step daemon does.
 */
public record Ack(int capabilityVersion, int slots) implements CiRunnerMessage {}

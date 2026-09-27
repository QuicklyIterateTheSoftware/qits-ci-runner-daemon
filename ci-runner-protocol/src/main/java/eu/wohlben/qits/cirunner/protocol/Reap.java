package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci → runner: remove a step's container, running or not. The name is carried rather than
 * looked up because it is qits-ci's own ({@code CiDaemonLauncher.containerName}) and the one it put
 * into the {@link Launch}'s spec; a runner that had to remember it would be one more place for the
 * two to disagree. The runner answers {@link Reaped} whatever docker said.
 */
public record Reap(String runId, int stepIndex, String containerName) implements CiRunnerMessage {}

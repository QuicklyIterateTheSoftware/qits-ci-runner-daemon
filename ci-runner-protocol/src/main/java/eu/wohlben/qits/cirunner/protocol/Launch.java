package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci → runner: start one step's container. The runner translates {@code workloadSpec} to
 * {@code docker run} on its host and answers {@link Launched} or {@link LaunchFailed}. Nothing else
 * about the step crosses this socket: the step's daemon dials qits-ci's control socket itself and
 * the script arrives there as a reply, so logs, timeouts and the step protocol never touch the
 * runner.
 */
public record Launch(String runId, int stepIndex, WorkloadSpec workloadSpec)
    implements CiRunnerMessage {}

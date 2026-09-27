package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci → runner: the run is closed — its verdict is recorded and no further {@link Launch} will
 * come for it — so the slot it held is free.
 *
 * <p><b>Added to the brief's frame list on purpose.</b> qits-ci drives the run and the runner only
 * starts containers, so without this frame the runner cannot tell "the last step was reaped" from
 * "the next step has not been launched yet": a red step skips the rest, a step count is not on the
 * wire, and inferring the end from a silence would either free a slot a run still needs or hold one
 * forever. The host knows, so the host says.
 */
public record Released(String runId) implements CiRunnerMessage {}

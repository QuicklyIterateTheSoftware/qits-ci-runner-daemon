package eu.wohlben.qits.cirunner.protocol;

/**
 * The first frame a runner sends after the upgrade: which binary it is, which protocol it speaks,
 * how many runs its operator configured it for and what its host can do. The host answers with
 * {@link Ack}.
 *
 * <p>{@code slots} is the operator's number ({@code QITS_CI_RUNNER_SLOTS}) and it is advisory: the
 * runner row in qits-ci is what an admin edits, and {@link Ack#slots()} is the cap the runner obeys.
 * It is on the wire so the host can show — and an admin can see — a runner whose machine was set up
 * for fewer runs than the row grants it.
 *
 * <p><b>Identity is not in it.</b> The connection was authenticated by the bearer on the upgrade,
 * minted from the client this runner registered — the host knows which runner this is before it
 * reads a frame, and a runner id here would only be a claim to check against that.
 */
public record Hello(
    String runnerVersion, int capabilityVersion, int slots, Capabilities capabilities)
    implements CiRunnerMessage {}

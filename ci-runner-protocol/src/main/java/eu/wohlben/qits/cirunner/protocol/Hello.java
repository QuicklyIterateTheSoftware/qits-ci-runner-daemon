package eu.wohlben.qits.cirunner.protocol;

/**
 * The first frame a runner sends after the upgrade: which binary it is, which protocol it speaks
 * and what its host can do. The host answers with {@link Ack}.
 *
 * <p><b>Identity is not in it.</b> The connection was authenticated by the bearer on the upgrade,
 * minted from the client this runner registered — the host knows which runner this is before it
 * reads a frame, and a runner id here would only be a claim to check against that.
 */
public record Hello(String runnerVersion, int capabilityVersion, Capabilities capabilities)
    implements CiRunnerMessage {}

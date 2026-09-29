package eu.wohlben.qits.cirunner.protocol;

import java.util.List;

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
 *
 * <p><b>{@code runnerVersion} is FROZEN</b> — the field name {@code "runnerVersion"}, a string, the
 * binary's {@link CiRunnerBinary#VERSION}. It is what qits-ci compares with its pin to decide on
 * {@link Upgrade}, so a runner of any age must keep being recognisable by it; see {@link Upgrade}.
 *
 * <p><b>{@code heldRuns} is what a runner that lost its socket still holds</b> — the runs it took on
 * an earlier connection of this same process, whose containers it kept through the blip — and is
 * empty on a runner's first connection. It is a claim, not a request: the host keeps the runs it is
 * still waiting on the runner for and names them in {@link Ack#adoptedRuns()}, and every other run
 * the runner claimed is its own to cancel. Added rather than a capability bump: a host older than it
 * ignores the key, and its {@code Ack} then adopts nothing, which is exactly what that host did.
 */
public record Hello(
    String runnerVersion,
    int capabilityVersion,
    int slots,
    Capabilities capabilities,
    List<String> heldRuns)
    implements CiRunnerMessage {

  public Hello {
    heldRuns = heldRuns == null ? List.of() : List.copyOf(heldRuns);
  }

  /** A first connection, or a runner from before {@code heldRuns}: nothing carried across. */
  public Hello(String runnerVersion, int capabilityVersion, int slots, Capabilities capabilities) {
    this(runnerVersion, capabilityVersion, slots, capabilities, List.of());
  }
}

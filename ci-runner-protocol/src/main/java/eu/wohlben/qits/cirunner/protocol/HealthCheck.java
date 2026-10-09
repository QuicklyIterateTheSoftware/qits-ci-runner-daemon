package eu.wohlben.qits.cirunner.protocol;

/**
 * qits-ci → runner: "run your health checks and tell me what you found." Answered by {@link
 * HealthChecked}, carrying the same {@code requestId}, once every check has run — each under its
 * own deadline, so an answer always comes unless the runner is gone.
 *
 * <p>The node report is a diagnosis, not the gate: quarantine still follows the host's pseudo-build
 * alone. The wire shape is qits-runner-protocol's {@code HealthWire}, shared with every runner kind;
 * this record stays CI's own so it can sit in {@link CiRunnerMessage}'s sealed list.
 *
 * <p>Additive: an older runner's codec has no arm for {@code "healthCheck"} and drops it as an
 * unknown type, so a host must time an unanswered request out rather than wait. {@link
 * CiRunnerProtocol#CAPABILITY_VERSION} does not move for it.
 *
 * @param requestId the asker's correlation id, echoed in the answer; null when it sent none
 * @param image the step image the {@code stepImage} check looks for, already resolved by the host
 *     the way a step's is (with its registry); null for the runner's default — a CI-only field,
 *     written after {@code HealthWire}'s and left off when absent
 */
public record HealthCheck(String requestId, String image) implements CiRunnerMessage {

  /** A request that leaves the step image to the runner's default. */
  public HealthCheck(String requestId) {
    this(requestId, null);
  }
}

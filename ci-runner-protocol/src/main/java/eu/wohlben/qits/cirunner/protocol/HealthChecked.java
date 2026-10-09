package eu.wohlben.qits.cirunner.protocol;

import eu.wohlben.qits.runner.protocol.health.CheckResult;
import eu.wohlben.qits.runner.protocol.health.HealthReport;
import java.util.List;

/**
 * Runner → qits-ci: the answer to a {@link HealthCheck} — {@code ok} only when every named check
 * passed, a line for a person, and each check's outcome in the runner's order ({@code docker},
 * {@code nodeInventory}, {@code session}, then CI's own: {@code buildkit}, {@code network}, {@code
 * idRange}, {@code stepImage}). A new check is a new entry with its own {@code data}, never a new
 * field.
 *
 * <p>The shape is qits-runner-protocol's {@link HealthReport}, encoded by its {@code HealthWire}:
 * {@code requestId} and {@code checks} are left off when absent, and read as absent from a peer that
 * never sends them.
 *
 * @param ok every check passed
 * @param detail {@code "all N checks passed"}, or each failure as {@code "<name>: <detail>"}
 * @param requestId the request this answers; null when the request carried none
 * @param checks each check's outcome, in order; empty, never null
 */
public record HealthChecked(boolean ok, String detail, String requestId, List<CheckResult> checks)
    implements CiRunnerMessage {

  public HealthChecked {
    checks = checks == null ? List.of() : List.copyOf(checks);
  }

  /** The frame for {@code report}. */
  public static HealthChecked of(HealthReport report) {
    return new HealthChecked(report.ok(), report.detail(), report.requestId(), report.checks());
  }

  /** This frame as the shared report shape. */
  public HealthReport report() {
    return new HealthReport(ok, detail, requestId, checks);
  }
}

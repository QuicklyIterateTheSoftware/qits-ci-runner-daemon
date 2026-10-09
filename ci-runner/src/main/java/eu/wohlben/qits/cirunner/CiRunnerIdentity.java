package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.CiRunnerBinary;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol;
import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;

/**
 * This runner as qits-runner-javalib's {@link RunnerIdentity} — the one its {@code
 * RunnerIdentityTest.theCiShapedIdentityDerivesTodaysCiSpellingsExactly} constructs and pins, so
 * every name it derives is the spelling this daemon already uses: {@code qits.ci.runner} ({@link
 * RunnerArgv#RUNNER_LABEL}), {@code qits.ci.runner.run}, {@code .process}, {@code .version}, and the
 * runner container {@code qits-ci-runner-<id8>-<version>} ({@link RunnerArgv#containerName}).
 * {@code RunnerIdentityTest} here holds the two side by side.
 *
 * <p>Used for the health checks' {@code nodeInventory} only. Nothing else here is derived from it
 * yet: moving the runner onto the toolkit's runtime is qits-772.
 */
final class CiRunnerIdentity {

  private CiRunnerIdentity() {}

  /** At this binary's own version. */
  static RunnerIdentity of() {
    return of(CiRunnerBinary.VERSION);
  }

  static RunnerIdentity of(String version) {
    return new RunnerIdentity(
        "ci",
        "QITS_CI_RUNNER_",
        "qits.ci.runner",
        "qits-ci-runner",
        "qits/qits-ci-runner",
        "/var/lib/qits-ci-runner",
        "/ci/api/runners/{id}/register",
        "qits-ci-runner",
        version,
        CiRunnerProtocol.CAPABILITY_VERSION,
        new RunnerVocabulary(
            CiRunnerProtocol.Field.HELD_RUNS, CiRunnerProtocol.Field.ADOPTED_RUNS),
        "run");
  }
}

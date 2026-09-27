package eu.wohlben.qits.cirunner;

import java.nio.file.Path;

/**
 * Everything the runner is handed before anything is dialled, already parsed. Built by {@link
 * #parse} from the raw environment strings so that a bad value is a sentence naming the variable,
 * not a SmallRye conversion stack trace.
 *
 * <p>The registration token is here and nowhere else: {@link Registration} reads it once, when there
 * is no client yet (or the token changed), and nothing ever logs it.
 */
public record RunnerEnv(
    String url,
    String runnerId,
    String registrationToken,
    Path stateDir,
    int slots,
    String dockerBinary,
    long dockerTimeoutSeconds,
    String buildkitImage) {

  /** {@code QITS_CI_RUNNER_STATE_DIR}'s default — what the unit's {@code StateDirectory=} makes. */
  public static final String DEFAULT_STATE_DIR = "/var/lib/qits-ci-runner";

  /**
   * The same pin qits-containers' {@code qits.containers.buildkit.image} carries. Bump the two
   * together: a step image's buildctl and the builder it dials tolerate minor skew, but nobody
   * should have to remember that.
   */
  public static final String DEFAULT_BUILDKIT_IMAGE = "moby/buildkit:v0.33.0";

  /** The advertised slot count when the operator set none. */
  public static final int DEFAULT_SLOTS = 1;

  /** A misconfiguration, carrying the one line the runner prints before exiting 2. */
  public static final class Invalid extends Exception {
    public Invalid(String message) {
      super(message);
    }
  }

  /**
   * Parse the raw values. {@code null} and blank both mean "unset". The url and the id are the two
   * values nothing can default; the token's absence is only a problem when there is no client yet,
   * which {@link Registration} decides.
   */
  public static RunnerEnv parse(
      String url,
      String runnerId,
      String registrationToken,
      String stateDir,
      String slots,
      String dockerBinary,
      String dockerTimeout,
      String buildkitImage)
      throws Invalid {
    if (blank(url)) {
      throw new Invalid("QITS_CI_RUNNER_URL is not set");
    }
    if (!url.trim().matches("(?i)https?://[^/\\s]+(/\\S*)?")) {
      throw new Invalid("QITS_CI_RUNNER_URL is not an http(s) url: '" + url.trim() + "'");
    }
    if (blank(runnerId)) {
      throw new Invalid("QITS_CI_RUNNER_ID is not set");
    }
    // A path segment of the register door and a docker label value: the charset keeps it both
    // without escaping, and an id qits-ci minted is always inside it.
    if (!runnerId.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
      throw new Invalid("QITS_CI_RUNNER_ID is not a runner id: '" + runnerId.trim() + "'");
    }
    return new RunnerEnv(
        url.trim().replaceAll("/+$", ""),
        runnerId.trim(),
        blank(registrationToken) ? "" : registrationToken.trim(),
        Path.of(blank(stateDir) ? DEFAULT_STATE_DIR : stateDir.trim()),
        positive("QITS_CI_RUNNER_SLOTS", slots, DEFAULT_SLOTS),
        blank(dockerBinary) ? "docker" : dockerBinary.trim(),
        positive("QITS_CI_RUNNER_DOCKER_TIMEOUT", stripSeconds(dockerTimeout), 120),
        blank(buildkitImage) ? DEFAULT_BUILDKIT_IMAGE : buildkitImage.trim());
  }

  /** {@code 120} and {@code 120s} both mean seconds; nothing else is accepted. */
  private static String stripSeconds(String value) {
    return value == null ? null : value.trim().replaceFirst("(?i)s$", "");
  }

  private static int positive(String variable, String value, int fallback) throws Invalid {
    if (blank(value)) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed > 0) {
        return parsed;
      }
    } catch (NumberFormatException ignored) {
      // falls through to the one sentence below
    }
    throw new Invalid(variable + " is not a positive whole number: '" + value.trim() + "'");
  }

  static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}

package eu.wohlben.qits.cirunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    String buildkitImage,
    List<String> buildkitHttpRegistries,
    List<String> buildkitRegistryMirrors,
    long rolloverTimeoutSeconds,
    boolean selfUpdate,
    String buildkitStateVolume) {

  /**
   * {@code QITS_CI_RUNNER_SELF_UPDATE}: whether an {@code Upgrade} makes this runner roll itself over
   * ({@link Rollover}). Default true — a runner a person installed has nobody else to update it.
   * False is a deployer-managed runner, a swarm service its deployer replaces: it ignores every
   * {@code Upgrade} and says so in its capability labels ({@link Main#SELF_UPDATE_LABEL}).
   */
  public static final String SELF_UPDATE = "QITS_CI_RUNNER_SELF_UPDATE";

  /**
   * {@code QITS_CI_RUNNER_BUILDKIT_STATE_VOLUME}: the volume {@link BuildPlane}'s builder keeps its
   * content store in. Read here (rather than left a {@link BuildPlane} constant) because {@link
   * Decommission}'s helper — a fresh process, not this one — needs the same value to remove the
   * volume this runner actually used, not the default. Defaults to {@link BuildPlane#STATE_VOLUME},
   * the platform builder's own volume: sharing it costs nothing but a warm cache on the one host
   * where both run, which is the point of the default.
   */
  public static final String BUILDKIT_STATE_VOLUME = "QITS_CI_RUNNER_BUILDKIT_STATE_VOLUME";

  /**
   * A registry as buildkitd.toml names it: {@code host[:port]}. Checked here because the value is
   * rendered inside a TOML string — a quote or a newline in it would be configuration nobody wrote.
   */
  private static final Pattern REGISTRY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,252}(:[0-9]{1,5})?");

  /** A mirror target may carry a path prefix ({@code mirror:8080/hub}), the way PlatformBuildkit's do. */
  private static final Pattern MIRROR_TARGET =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,252}(:[0-9]{1,5})?(/[A-Za-z0-9._-]+)*");

  /** {@code QITS_CI_RUNNER_STATE_DIR}'s default — where the container contract mounts the state volume. */
  public static final String DEFAULT_STATE_DIR = "/var/lib/qits-ci-runner";

  /**
   * The same pin qits-containers' {@code qits.containers.buildkit.image} carries. Bump the two
   * together: a step image's buildctl and the builder it dials tolerate minor skew, but nobody
   * should have to remember that.
   */
  public static final String DEFAULT_BUILDKIT_IMAGE = "moby/buildkit:v0.33.0";

  /** The advertised slot count when the operator set none. */
  public static final int DEFAULT_SLOTS = 1;

  /**
   * How long a successor has to take over before it is removed — {@code
   * QITS_CI_RUNNER_ROLLOVER_TIMEOUT}. Three minutes is a pull-free container start, a registration
   * that is not needed, a token mint and a WebSocket upgrade, with room for a slow host.
   */
  public static final int DEFAULT_ROLLOVER_TIMEOUT_SECONDS = 180;

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
    return parse(
        url, runnerId, registrationToken, stateDir, slots, dockerBinary, dockerTimeout,
        buildkitImage, null, null);
  }

  /**
   * The same, with the builder's registry configuration: {@code QITS_CI_RUNNER_BUILDKIT_HTTP_REGISTRIES}
   * (comma list of {@code host[:port]}) and {@code QITS_CI_RUNNER_BUILDKIT_REGISTRY_MIRRORS} (comma list
   * of {@code from=to}). Both default to empty, which is right for a runner on the edge plane — every
   * registry there is https and resolves publicly. A runner on the platform host's {@code qits-net}
   * sets them to qits-containers' {@code qits.containers.buildkit.http-registries} / {@code
   * registry-mirrors} values, or its builder cannot push to the platform's plain-HTTP registry.
   */
  public static RunnerEnv parse(
      String url,
      String runnerId,
      String registrationToken,
      String stateDir,
      String slots,
      String dockerBinary,
      String dockerTimeout,
      String buildkitImage,
      String buildkitHttpRegistries,
      String buildkitRegistryMirrors)
      throws Invalid {
    return parse(
        url, runnerId, registrationToken, stateDir, slots, dockerBinary, dockerTimeout,
        buildkitImage, buildkitHttpRegistries, buildkitRegistryMirrors, null);
  }

  /**
   * The same, with {@code QITS_CI_RUNNER_ROLLOVER_TIMEOUT}: seconds a successor container has to
   * take over before the old process removes it and stays (see {@link Rollover}).
   */
  public static RunnerEnv parse(
      String url,
      String runnerId,
      String registrationToken,
      String stateDir,
      String slots,
      String dockerBinary,
      String dockerTimeout,
      String buildkitImage,
      String buildkitHttpRegistries,
      String buildkitRegistryMirrors,
      String rolloverTimeout)
      throws Invalid {
    return parse(
        url, runnerId, registrationToken, stateDir, slots, dockerBinary, dockerTimeout,
        buildkitImage, buildkitHttpRegistries, buildkitRegistryMirrors, rolloverTimeout, null);
  }

  /** The same, with {@link #SELF_UPDATE}: {@code true}/{@code false} (or {@code 1}/{@code 0}). */
  public static RunnerEnv parse(
      String url,
      String runnerId,
      String registrationToken,
      String stateDir,
      String slots,
      String dockerBinary,
      String dockerTimeout,
      String buildkitImage,
      String buildkitHttpRegistries,
      String buildkitRegistryMirrors,
      String rolloverTimeout,
      String selfUpdate)
      throws Invalid {
    return parse(
        url, runnerId, registrationToken, stateDir, slots, dockerBinary, dockerTimeout,
        buildkitImage, buildkitHttpRegistries, buildkitRegistryMirrors, rolloverTimeout,
        selfUpdate, null);
  }

  /** The same, with {@link #BUILDKIT_STATE_VOLUME}. */
  public static RunnerEnv parse(
      String url,
      String runnerId,
      String registrationToken,
      String stateDir,
      String slots,
      String dockerBinary,
      String dockerTimeout,
      String buildkitImage,
      String buildkitHttpRegistries,
      String buildkitRegistryMirrors,
      String rolloverTimeout,
      String selfUpdate,
      String buildkitStateVolume)
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
        blank(buildkitImage) ? DEFAULT_BUILDKIT_IMAGE : buildkitImage.trim(),
        httpRegistries(buildkitHttpRegistries),
        mirrors(buildkitRegistryMirrors),
        positive(
            "QITS_CI_RUNNER_ROLLOVER_TIMEOUT",
            stripSeconds(rolloverTimeout),
            DEFAULT_ROLLOVER_TIMEOUT_SECONDS),
        bool(SELF_UPDATE, selfUpdate, true),
        blank(buildkitStateVolume) ? BuildPlane.STATE_VOLUME : buildkitStateVolume.trim());
  }

  private static boolean bool(String variable, String value, boolean fallback) throws Invalid {
    if (blank(value)) {
      return fallback;
    }
    return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "true", "1" -> true;
      case "false", "0" -> false;
      default -> throw new Invalid(variable + " is not true or false: '" + value.trim() + "'");
    };
  }

  /** A CI url whose host is the edge's {@code ci.} application name, and what follows it. */
  private static final Pattern CI_HOST = Pattern.compile("(?i)(https?://)ci\\.([^/\\s]+)(/\\S*)?");

  /** Where qits-observability's OTLP receiver lives below its host — its own root path. */
  public static final String TELEMETRY_PATH = "/observability/api/otel";

  /**
   * {@code QITS_CI_RUNNER_TELEMETRY_URL}: the OTLP endpoint the runner's log is shipped to, or null
   * for none.
   *
   * <p>Unset ({@code null}) derives it from the CI's url, the way the edge names every application:
   * {@code https://ci.qits.example.eu} → {@code https://observability.qits.example.eu} + {@link
   * #TELEMETRY_PATH}. A CI url whose host does not start with {@code ci.} — the platform's own
   * network, an address — has no such sibling, and derives nothing. Set but empty switches it off,
   * which is why {@link Main} reads this one variable raw: SmallRye cannot tell empty from unset.
   */
  public static String telemetryUrl(String raw, String ciUrl) throws Invalid {
    if (raw != null) {
      if (raw.isBlank()) {
        return null;
      }
      String url = raw.trim().replaceAll("/+$", "");
      if (!url.matches("(?i)https?://[^/\\s]+(/\\S*)?")) {
        throw new Invalid("QITS_CI_RUNNER_TELEMETRY_URL is not an http(s) url: '" + url + "'");
      }
      return url;
    }
    Matcher ci = CI_HOST.matcher(ciUrl == null ? "" : ciUrl);
    return ci.matches() ? ci.group(1) + "observability." + ci.group(2) + TELEMETRY_PATH : null;
  }

  private static List<String> httpRegistries(String value) throws Invalid {
    List<String> registries = new ArrayList<>();
    for (String item : items(value)) {
      if (!REGISTRY.matcher(item).matches()) {
        throw new Invalid(
            "QITS_CI_RUNNER_BUILDKIT_HTTP_REGISTRIES has an entry that is not host[:port]: '"
                + item + "'");
      }
      registries.add(item);
    }
    return List.copyOf(registries);
  }

  private static List<String> mirrors(String value) throws Invalid {
    List<String> mirrors = new ArrayList<>();
    for (String item : items(value)) {
      int split = item.indexOf('=');
      if (split <= 0
          || !REGISTRY.matcher(item.substring(0, split)).matches()
          || !MIRROR_TARGET.matcher(item.substring(split + 1)).matches()) {
        throw new Invalid(
            "QITS_CI_RUNNER_BUILDKIT_REGISTRY_MIRRORS has an entry that is not from=to: '"
                + item + "'");
      }
      mirrors.add(item);
    }
    return List.copyOf(mirrors);
  }

  private static List<String> items(String value) {
    List<String> items = new ArrayList<>();
    if (blank(value)) {
      return items;
    }
    for (String item : value.split(",")) {
      if (!item.isBlank()) {
        items.add(item.trim());
      }
    }
    return items;
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

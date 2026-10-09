package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.health.HealthContext;
import eu.wohlben.qits.runner.toolkit.health.RunnerHealthCheck;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code stepImage} health check: the image a health-check run starts ({@link
 * CiHealth.StepImageTarget} — the request's, or {@link CiHealth#defaultStepImage}) is on this node,
 * or can be pulled. Its {@code data}: {@code {image, requested, present, pulled}}, plus {@code
 * authRequired: true} on the one failure this check treats as healthy (see below).
 *
 * <p>The way a launch has its image ({@code Launcher.ensureImage}): {@code docker image inspect},
 * and only when that finds nothing a {@code docker pull}. A present image is never pulled again. A
 * pull is the one thing a health check here writes to the node, and it is what the next run would
 * do anyway; a pulled image is recorded in {@link StepImages} like a launch's, so housekeeping
 * removes it once idle.
 *
 * <p><b>Under the host's docker config, with no run's login.</b> A launch borrows its run's
 * registry credential for the pull; a health check has no run, and a credential does not travel on
 * a health frame. So on a node whose registry refuses an anonymous pull, an absent image is not a
 * node fault: a real run brings its own credential and would pull it anyway. {@link
 * #isAuthRefusal} recognises that one class of pull failure — docker's wording for "this registry
 * wants a credential I don't have" — and reports it {@code ok} with {@code authRequired: true}
 * instead of failing the node. Every other pull failure (timeout, connection refused, manifest
 * unknown, no such host, …) still fails the check as before.
 *
 * <p><b>Its own, longer deadline</b> ({@link #DEADLINE}, the pull under {@link #PULL_DEADLINE}): a
 * cold pull of the step image takes minutes, far past the registry's default. Both stay under
 * qits-ci's five-minute wait for an answer.
 */
@Singleton
public class StepImageCheck implements RunnerHealthCheck {

  static final String NAME = "stepImage";

  /** The pull's deadline. */
  static final Duration PULL_DEADLINE = Duration.ofSeconds(180);

  /** The check's: the pull plus the inspect before it. */
  static final Duration DEADLINE = PULL_DEADLINE.plusSeconds(20);

  /**
   * Substrings of a failed pull's detail that mean "the registry wants a credential", not "this
   * node is broken" — matched case-insensitively against docker's own wording, never against ours.
   * Each is docker's or the registry's phrasing for one class of refusal, seen on a real pull under
   * no login:
   *
   * <ul>
   *   <li>{@code client credentials required} — the platform registry's own refusal of an
   *       anonymous pull, the one live data named this ticket after;
   *   <li>{@code unauthorized}, {@code authentication required} — docker's own generic wording for
   *       a v2 registry's 401;
   *   <li>{@code no basic auth credentials} — a registry that answers a bare basic challenge;
   *   <li>{@code denied: requested access to the resource is denied} — a registry that knows the
   *       repository exists but will not say so to an anonymous caller;
   *   <li>{@code pull access denied} — docker's own wrapper message ahead of the line above, seen
   *       without it on some registries.
   * </ul>
   *
   * <p>Kept tight on purpose, and as a list of literal phrases rather than a loose pattern: a
   * network failure, a bad host name or an unknown manifest must keep failing the check, and a
   * matcher that is too eager would hide a node that is genuinely unable to pull.
   */
  private static final List<String> AUTH_REFUSAL_PHRASES =
      List.of(
          "client credentials required",
          "unauthorized",
          "authentication required",
          "no basic auth credentials",
          "denied: requested access to the resource is denied",
          "pull access denied");

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Duration deadline() {
    return DEADLINE;
  }

  @Override
  public Result run(HealthContext ctx) {
    CiHealth.StepImageTarget target =
        ctx.lookup(CiHealth.StepImageTarget.class)
            .orElseThrow(() -> new IllegalStateException("no step image in the health context"));
    String image = target.image();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("image", image);
    data.put("requested", target.requested());
    data.put("present", false);
    data.put("pulled", false);
    DockerCommand docker = ctx.docker();
    List<String> inspect;
    List<String> pull;
    try {
      inspect = RunnerArgv.imageInspect(docker.binary(), image);
      pull = RunnerArgv.pull(docker.binary(), image);
    } catch (IllegalArgumentException refused) {
      return Result.failed("refused by the runner: " + refused.getMessage(), data);
    }
    if (docker.run(inspect).ok()) {
      data.put("present", true);
      return Result.ok(image + " is present", data);
    }
    RunnerDocker.Result pulled = docker.run(pull, PULL_DEADLINE);
    if (!pulled.ok()) {
      if (isAuthRefusal(pulled.detail())) {
        data.put("authRequired", true);
        return Result.ok(
            image + " is not on this node; the registry wants a run's credential, which every"
                + " run brings",
            data);
      }
      return Result.failed(
          "docker pull "
              + image
              + " failed (under this host's docker login, not a run's): "
              + tail(pulled.detail()),
          data);
    }
    ctx.lookup(StepImages.class).ifPresent(images -> images.used(image));
    data.put("present", true);
    data.put("pulled", true);
    return Result.ok(image + " was not here and was pulled", data);
  }

  /**
   * Whether a failed pull's detail is the registry asking for a credential rather than any other
   * failure — see {@link #AUTH_REFUSAL_PHRASES}.
   */
  private static boolean isAuthRefusal(String detail) {
    String lower = detail.toLowerCase(Locale.ROOT);
    return AUTH_REFUSAL_PHRASES.stream().anyMatch(lower::contains);
  }

  /** docker's verdict is its last line; a pull's progress before it is noise in a report. */
  private static String tail(String detail) {
    return detail.length() <= 500 ? detail : "…" + detail.substring(detail.length() - 500);
  }
}

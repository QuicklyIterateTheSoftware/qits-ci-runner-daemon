package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.health.HealthContext;
import eu.wohlben.qits.runner.toolkit.health.RunnerHealthCheck;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code stepImage} health check: the image a health-check run starts ({@link
 * CiHealth.StepImageTarget} — the request's, or {@link CiHealth#defaultStepImage}) is on this node,
 * or can be pulled. Its {@code data}: {@code {image, requested, present, pulled}}.
 *
 * <p>The way a launch has its image ({@code Launcher.ensureImage}): {@code docker image inspect},
 * and only when that finds nothing a {@code docker pull}. A present image is never pulled again. A
 * pull is the one thing a health check here writes to the node, and it is what the next run would
 * do anyway; a pulled image is recorded in {@link StepImages} like a launch's, so housekeeping
 * removes it once idle.
 *
 * <p><b>Under the host's docker config, with no run's login.</b> A launch borrows its run's
 * registry credential for the pull; a health check has no run, and a credential does not travel on
 * a health frame. So on a node whose registry refuses an anonymous pull, an image that is not
 * already here fails this check where a real run would still pull it — the detail says so.
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

  /** docker's verdict is its last line; a pull's progress before it is noise in a report. */
  private static String tail(String detail) {
    return detail.length() <= 500 ? detail : "…" + detail.substring(detail.length() - 500);
  }
}

package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.cirunner.protocol.CiRunnerBinary;
import eu.wohlben.qits.runner.protocol.health.HealthReport;
import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.health.HealthChecks;
import eu.wohlben.qits.runner.toolkit.health.HealthContext;
import eu.wohlben.qits.runner.toolkit.health.RunnerHealthCheck;
import eu.wohlben.qits.runner.toolkit.health.SessionFacts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The node health check a {@code HealthCheck} frame asks for: qits-runner-javalib's {@link
 * HealthChecks} with its three defaults ({@code docker}, {@code nodeInventory}, {@code session}),
 * then CI's own ({@code buildkit}, {@code network}, {@code idRange}, {@code stepImage}), each run
 * under its own deadline so one hung probe fails by itself and the rest are still reported.
 *
 * <p><b>The CI checks are ArC beans</b> ({@link BuildkitCheck} and its siblings, each a
 * {@code @Singleton} with no constructor argument) that {@link Main} collects with {@code @All
 * List<RunnerHealthCheck>} — resolved at build time, so the native image needs no reflection — and
 * hands to {@link #registry}. A check reaches what it reads through {@link HealthContext#lookup}:
 * {@link BuildPlane} (the one the next launch would ensure), {@link Capabilities}, {@link
 * StepImages} and {@link StepImageTarget}, put there per request by {@link #check}.
 *
 * <p><b>The order is fixed here, not by ArC.</b> {@code @All} promises no order beyond priority, and
 * the wire carries the checks in registration order, which a reader of two reports compares. So
 * {@link #ordered} puts the known CI checks in {@link #CI_ORDER} and any other bean after them by
 * name.
 *
 * <p>A plain class like everything below {@link Main}, so {@code RunnerMainTest} drives it with the
 * same beans constructed by hand ({@link #builtIn}).
 */
public final class CiHealth {

  /**
   * The step image the {@code stepImage} check looks for when the request names none: the image
   * every platform recipe opens with, as qits-ci-service's {@code CiStepImage} resolves a bare
   * platform name — see {@link #defaultStepImage}.
   */
  public static final String DEFAULT_STEP_IMAGE = "qits/build-images/ci-base:latest";

  /** The CI checks' wire order; any other {@link RunnerHealthCheck} bean follows, by name. */
  public static final List<String> CI_ORDER =
      List.of(BuildkitCheck.NAME, NetworkCheck.NAME, IdRangeCheck.NAME, StepImageCheck.NAME);

  /**
   * What the {@code stepImage} check is to look for.
   *
   * @param image the reference, exactly as {@code docker pull} takes it
   * @param requested whether the request named it, rather than the runner's default
   */
  public record StepImageTarget(String image, boolean requested) {}

  private final HealthChecks checks;
  private final DockerCommand docker;
  private final Supplier<BuildPlane> buildPlane;
  private final Capabilities capabilities;
  private final StepImages stepImages;
  private final String defaultStepImage;

  /**
   * @param buildPlane read at each request — {@link Launcher#buildPlane}, which an {@code Ack}
   *     swaps
   * @param defaultStepImage the {@code stepImage} check's image when a request names none
   */
  public CiHealth(
      HealthChecks checks,
      DockerCommand docker,
      Supplier<BuildPlane> buildPlane,
      Capabilities capabilities,
      StepImages stepImages,
      String defaultStepImage) {
    this.checks = Objects.requireNonNull(checks, "checks");
    this.docker = Objects.requireNonNull(docker, "docker");
    this.buildPlane = Objects.requireNonNull(buildPlane, "buildPlane");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.stepImages = stepImages;
    this.defaultStepImage = Objects.requireNonNull(defaultStepImage, "defaultStepImage");
  }

  /**
   * The registry: the javalib's defaults in their order, then {@code ciChecks} in {@link #ordered}
   * order.
   *
   * @param defaultDeadline a check's deadline when it names none — the docker deadline × 3, room for
   *     the three or so docker calls a check makes
   */
  public static HealthChecks registry(
      Duration defaultDeadline,
      String runnerId,
      Collection<? extends RunnerHealthCheck> ciChecks) {
    return HealthChecks.withDefaults(
            defaultDeadline,
            new HealthChecks.NodeFacts(CiRunnerIdentity.of(), runnerId, CiRunnerBinary.VERSION))
        .registerAll(ordered(ciChecks));
  }

  /** {@link #CI_ORDER} first, then every other check by name: deterministic whatever ArC hands. */
  public static List<RunnerHealthCheck> ordered(Collection<? extends RunnerHealthCheck> ciChecks) {
    List<RunnerHealthCheck> sorted = new ArrayList<>(ciChecks);
    sorted.sort(
        Comparator.comparingInt(
                (RunnerHealthCheck c) -> {
                  int known = CI_ORDER.indexOf(c.name());
                  return known < 0 ? CI_ORDER.size() : known;
                })
            .thenComparing(RunnerHealthCheck::name));
    return sorted;
  }

  /** The CI checks constructed by hand, for a caller that is not ArC — the suite. */
  public static List<RunnerHealthCheck> builtIn() {
    return List.of(new BuildkitCheck(), new NetworkCheck(), new IdRangeCheck(), new StepImageCheck());
  }

  /**
   * This daemon's {@link Docker} as the toolkit's {@link DockerCommand}. A call under the default
   * deadline goes through {@code docker} itself; one under another deadline (the {@code stepImage}
   * pull) through a {@code forking} docker of that many seconds, made once per value — {@link
   * Docker}'s deadline is fixed at construction.
   */
  public static DockerCommand dockerCommand(
      String binary, Docker docker, long timeoutSeconds, LongFunction<Docker> forking) {
    Map<Long, Docker> byDeadline = new ConcurrentHashMap<>();
    byDeadline.put(timeoutSeconds, docker);
    return DockerCommand.of(
        binary,
        Duration.ofSeconds(timeoutSeconds),
        (argv, deadline) -> {
          long seconds = Math.max(1, (deadline.toMillis() + 999) / 1000);
          Docker.Result r = byDeadline.computeIfAbsent(seconds, forking::apply).run(argv);
          return new RunnerDocker.Result(r.exitCode(), r.stdout(), r.stderr(), r.timedOut(), false);
        });
  }

  /** A CI url whose host is the edge's {@code ci.} application name — {@link RunnerEnv}'s rule. */
  private static final Pattern CI_HOST = Pattern.compile("(?i)https?://ci\\.([^/:\\s]+)(:\\d+)?(/\\S*)?");

  /**
   * {@link #DEFAULT_STEP_IMAGE} on the platform registry the CI url names: {@code
   * https://ci.qits.example.eu} → {@code registry.qits.example.eu/qits/build-images/ci-base:latest},
   * the {@code registry.qits.<domain>} the release builds publish to (qits-731) and the same prefix
   * qits-ci-service's {@code CiStepImage} puts on a bare platform name. A url without the {@code ci.}
   * host — a runner on the platform host's network, say — leaves the bare name, which only a local
   * copy answers. A host that knows better names the image in the request.
   */
  public static String defaultStepImage(String ciUrl) {
    Matcher ci = ciUrl == null ? null : CI_HOST.matcher(ciUrl.strip());
    return ci != null && ci.matches()
        ? "registry." + ci.group(1) + "/" + DEFAULT_STEP_IMAGE
        : DEFAULT_STEP_IMAGE;
  }

  /** The registered checks' names, in wire order. */
  public List<String> names() {
    return checks.names();
  }

  /**
   * Run every check against the node as it is now and report. Blocks for as long as the slowest
   * deadlines allow, so never on an event loop.
   *
   * @param image the request's step image; null or blank for {@link #defaultStepImage}
   */
  public HealthReport check(String requestId, String image, SessionFacts facts) {
    boolean requested = image != null && !image.isBlank();
    Map<Class<?>, Object> services = new LinkedHashMap<>();
    services.put(BuildPlane.class, buildPlane.get());
    services.put(Capabilities.class, capabilities);
    if (stepImages != null) {
      services.put(StepImages.class, stepImages);
    }
    services.put(
        StepImageTarget.class,
        new StepImageTarget(requested ? image.strip() : defaultStepImage, requested));
    HealthContext ctx = HealthContext.of(docker, () -> facts, services);
    return HealthChecks.answer(requestId, checks.run(ctx));
  }

  /** Stop the checks' threads; the runner's end. */
  public void close() {
    checks.close();
  }
}

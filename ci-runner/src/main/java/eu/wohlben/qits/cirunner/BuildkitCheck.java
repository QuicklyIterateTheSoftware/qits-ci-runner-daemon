package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.runner.toolkit.health.HealthContext;
import eu.wohlben.qits.runner.toolkit.health.RunnerHealthCheck;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code buildkit} health check: the runner's own builder, {@value BuildPlane#CONTAINER}, is
 * running under the stamp the next build would start it with. Its {@code data}: {@code {container,
 * presence, state, stamp, configuredStamp, address, image, stateVolume}}.
 *
 * <p><b>Read-only</b> — {@link BuildPlane#builder()}, one {@code docker inspect}; never started,
 * created or replaced from here. A builder that was never created is <b>ok</b>: the builder comes up
 * on the first build that needs it ({@link BuildPlane#ensure}), so a node that only ever ran tests
 * has none and is not sick for it. A stale stamp fails: until the next build (or an idle
 * housekeeping pass) replaces it, a build would run under the old registry configuration.
 *
 * <p>An ArC bean for {@link Main}'s {@code @All List<RunnerHealthCheck>}, with no constructor
 * argument: what it reads, it looks up in the context ({@link CiHealth}).
 */
@Singleton
public class BuildkitCheck implements RunnerHealthCheck {

  static final String NAME = "buildkit";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Result run(HealthContext ctx) {
    BuildPlane plane =
        ctx.lookup(BuildPlane.class)
            .orElseThrow(() -> new IllegalStateException("no BuildPlane in the health context"));
    BuildPlane.Builder builder = plane.builder();
    String configured = plane.configuredStamp();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("container", BuildPlane.CONTAINER);
    data.put("presence", builder.presence().name());
    data.put("state", builder.status());
    data.put("stamp", builder.stamp());
    data.put("configuredStamp", configured);
    data.put("address", BuildPlane.ADDRESS);
    data.put("image", plane.image());
    data.put("stateVolume", plane.stateVolume());
    return switch (builder.presence()) {
      case ABSENT ->
          Result.ok("not started yet: no build on this node has needed " + BuildPlane.CONTAINER, data);
      case UNKNOWN ->
          Result.failed(
              "docker did not answer an inspect of " + BuildPlane.CONTAINER + ": " + builder.detail(),
              data);
      case FOREIGN ->
          Result.failed(
              "a container named "
                  + BuildPlane.CONTAINER
                  + " exists and is not this runner's builder (it has no "
                  + BuildPlane.STAMP_LABEL
                  + " label); a build here will refuse it",
              data);
      case PRESENT -> {
        if (!"running".equals(builder.status())) {
          yield Result.failed(
              BuildPlane.CONTAINER + " is " + builder.status() + "; the next build starts it", data);
        }
        if (!configured.equals(builder.stamp())) {
          yield Result.failed(
              BuildPlane.CONTAINER
                  + " runs stamp "
                  + builder.stamp()
                  + " but the configuration is "
                  + configured
                  + "; the next build (or an idle housekeeping pass) replaces it",
              data);
        }
        yield Result.ok(BuildPlane.CONTAINER + " is running, stamp " + configured, data);
      }
    };
  }
}

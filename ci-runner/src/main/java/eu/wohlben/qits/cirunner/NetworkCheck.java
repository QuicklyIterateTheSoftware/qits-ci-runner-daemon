package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.runner.toolkit.health.HealthContext;
import eu.wohlben.qits.runner.toolkit.health.RunnerHealthCheck;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code network} health check: the runner-owned bridge {@value BuildPlane#NETWORK}, which a
 * building step and the builder share, exists. Its {@code data}: {@code {network, presence, driver,
 * builder}}.
 *
 * <p><b>Read-only</b> — {@link BuildPlane#network()} and, only when the network is missing, {@link
 * BuildPlane#builder()}. {@link BuildPlane#ensure} creates the network together with the builder on
 * the first build, so a missing network is <b>ok</b> ("not needed yet") while no builder was ever
 * created, and fails once one exists: a builder whose network went is one no step can reach.
 */
@Singleton
public class NetworkCheck implements RunnerHealthCheck {

  static final String NAME = "network";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Result run(HealthContext ctx) {
    BuildPlane plane =
        ctx.lookup(BuildPlane.class)
            .orElseThrow(() -> new IllegalStateException("no BuildPlane in the health context"));
    BuildPlane.Network network = plane.network();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("network", BuildPlane.NETWORK);
    data.put("presence", network.presence().name());
    data.put("driver", network.driver());
    data.put("builder", null);
    return switch (network.presence()) {
      case PRESENT ->
          Result.ok(BuildPlane.NETWORK + " exists (" + network.driver() + ")", data);
      case UNKNOWN ->
          Result.failed(
              "docker did not answer an inspect of " + BuildPlane.NETWORK + ": " + network.detail(),
              data);
      case ABSENT, FOREIGN -> {
        BuildPlane.Builder builder = plane.builder();
        data.put("builder", builder.presence().name());
        yield builder.presence() == BuildPlane.Presence.ABSENT
            ? Result.ok(
                "not needed yet: " + BuildPlane.NETWORK + " is created with the builder", data)
            : Result.failed(
                BuildPlane.NETWORK
                    + " is missing while "
                    + BuildPlane.CONTAINER
                    + " is "
                    + builder.presence().name().toLowerCase(java.util.Locale.ROOT)
                    + "; the next build recreates it",
                data);
      }
    };
  }
}

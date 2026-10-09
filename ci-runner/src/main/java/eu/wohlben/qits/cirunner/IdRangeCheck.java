package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.Capabilities;
import eu.wohlben.qits.runner.toolkit.health.HealthContext;
import eu.wohlben.qits.runner.toolkit.health.RunnerHealthCheck;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code idRange} health check: this runner's user namespace maps the full uid/gid space, so
 * buildkit can unpack any image layer (qits-556). Fails exactly when the boot-time warning fires —
 * {@link Capabilities#narrowIdRange()}, with {@link IdRange#narrowWarning}'s words as its detail,
 * the operator's fix included. Its {@code data}: {@code {idRange, fullIdRange, narrow}}.
 *
 * <p>Reads the {@link Capabilities} this process announced in its {@code Hello} — what qits-ci
 * matches build steps on — rather than re-reading {@code /proc}: the map cannot change under a
 * running process. An unknown range ({@code null}) is ok, as qits-ci allows it.
 */
@Singleton
public class IdRangeCheck implements RunnerHealthCheck {

  static final String NAME = "idRange";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Result run(HealthContext ctx) {
    Capabilities caps =
        ctx.lookup(Capabilities.class)
            .orElseThrow(() -> new IllegalStateException("no Capabilities in the health context"));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("idRange", caps.idRange());
    data.put("fullIdRange", Capabilities.FULL_ID_RANGE);
    data.put("narrow", caps.narrowIdRange());
    if (caps.idRange() == null) {
      return Result.ok("unknown: this process's uid/gid maps could not be read", data);
    }
    if (caps.narrowIdRange()) {
      return Result.failed(IdRange.narrowWarning(caps.idRange()), data);
    }
    return Result.ok("maps " + caps.idRange() + " uids/gids", data);
  }
}

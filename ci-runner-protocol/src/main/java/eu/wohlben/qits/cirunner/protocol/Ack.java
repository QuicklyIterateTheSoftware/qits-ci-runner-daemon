package eu.wohlben.qits.cirunner.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * qits-ci's answer to {@link Hello}: the host's own {@link CiRunnerProtocol#CAPABILITY_VERSION}, the
 * number of runs this runner may hold at once, and — additively — the registry mirror map its
 * builder should use.
 *
 * <p><b>{@code slots} is the host's number and it wins.</b> The runner advertises what its operator
 * configured, but the runner row in qits-ci is what an admin edits, so the cap arrives here and the
 * runner never reserves past it. A runner that reads a version it does not know exits rather than
 * guessing, as the step daemon does. {@code slots} is not only the first {@code Ack}'s word either:
 * qits-ci re-sends {@code Ack} whenever the admin-edited cap changes, and the runner is expected to
 * pick up every one, not just the one that answered its {@code Hello}.
 *
 * <p><b>{@link #registryMirrors} is qits-ci's map from the platform's committed Dockerfile spelling
 * ({@code host[:port]}, e.g. {@code mirror.dev.localhost:8080}) to the PUBLIC name a runner outside
 * the platform's own network can actually resolve ({@code host[/path]}, e.g. {@code
 * mirror.qits.wohlben.eu}).</b> A remote runner's builder cannot reach the internal alias the
 * platform's own buildkitd rewrites those {@code FROM} lines to, so qits-ci — the one side that
 * knows both spellings — sends the rewrite instead of asking every operator to configure it by hand.
 * {@code null} means "this host has not sent one" (an older qits-ci, or nothing has changed since
 * the last one); an empty map is a deliberate "nothing to rewrite", not the same thing. Either way a
 * runner on a version older than this field simply does not read it: adding a field is not a
 * capability bump, so {@link CiRunnerProtocol#CAPABILITY_VERSION} does not move for this.
 *
 * <p><b>{@link #adoptedRuns} answers {@link Hello#heldRuns()}</b>: which of the runs the runner
 * carried across a lost socket the host is still driving, and so kept for it. Only the {@code Ack}
 * that answers a {@code Hello} carries it; {@code null} there — a host older than the field — adopts
 * nothing, and a runner cancels every run it carried, as it did before it carried any. A re-sent
 * {@code Ack} (a slot change) carries {@code null} too, and says nothing about held runs at all.
 */
public record Ack(
    int capabilityVersion,
    int slots,
    Map<String, String> registryMirrors,
    List<String> adoptedRuns)
    implements CiRunnerMessage {

  /**
   * Order is stamp material once a runner merges this into its builder's configuration
   * ({@code BuildPlane}), so the copy keeps insertion order rather than {@code Map.copyOf}'s
   * per-JVM-salted one.
   */
  public Ack {
    registryMirrors =
        registryMirrors == null
            ? null
            : Collections.unmodifiableMap(new LinkedHashMap<>(registryMirrors));
    adoptedRuns = adoptedRuns == null ? null : List.copyOf(adoptedRuns);
  }

  /** An {@code Ack} that says nothing about held runs — every one before the field, and a re-send. */
  public Ack(int capabilityVersion, int slots, Map<String, String> registryMirrors) {
    this(capabilityVersion, slots, registryMirrors, null);
  }

  /** Before qits-ci sent registry mirrors at all: no map, same as any other host on this wire. */
  public Ack(int capabilityVersion, int slots) {
    this(capabilityVersion, slots, null);
  }
}

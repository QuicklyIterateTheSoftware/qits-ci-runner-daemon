package eu.wohlben.qits.cirunner.protocol;

import java.util.Map;

/**
 * What a runner says about the host it runs on, in its {@link Hello}.
 *
 * <p>{@code docker} is the one capability qits-ci matches on today: a pipeline with a {@code
 * docker: true} step needs a runner that will bind the host's docker socket into a step, and a
 * runner advertising {@code false} is never handed one. {@code arch} and {@code os} are the Go/OCI
 * spellings ({@code amd64}, {@code linux}) because that is the vocabulary an image's platform is
 * already written in, and a later match against one should not need a translation table.
 *
 * <p><b>{@code labels} is a map, not a list</b>, decided here: a label that carries a value
 * ({@code disk=ssd}, {@code site=home}) is the shape a matcher eventually needs, and a bare tag is
 * expressible as a key with an empty value while the reverse is not. They are stored and advertised,
 * not yet matched — the epic says so and this record does not pretend otherwise.
 *
 * <p><b>{@code idRange} is how many uids (and gids) the runner's user namespace maps</b> — the
 * smaller of the {@code /proc/self/uid_map} and {@code gid_map} totals, and the runner's namespace is
 * its docker daemon's. A host that is not in a user namespace maps {@link #FULL_ID_RANGE}; rootless
 * docker or an unprivileged LXC typically maps 65536, and then buildkit cannot unpack a layer that
 * owns a file above it ({@code failed to Lchown}, qits-556), so qits-ci does not reserve a
 * build-plane run onto such a runner. {@code null} is <i>unknown</i> — a runner older than the field,
 * or one that could not read its maps — and unknown is allowed, because refusing it would strand
 * every runner released before this line on the first upgrade of qits-ci.
 */
public record Capabilities(
    boolean docker, String arch, String os, Map<String, String> labels, Long idRange) {

  /** What an id map covers when it is the whole 32-bit id space: {@code 0 0 4294967295}. */
  public static final long FULL_ID_RANGE = 4294967295L;

  public Capabilities {
    labels = labels == null ? Map.of() : Map.copyOf(labels);
  }

  /** A runner that does not say its id range — every runner before the field existed. */
  public Capabilities(boolean docker, String arch, String os, Map<String, String> labels) {
    this(docker, arch, os, labels, null);
  }

  /** Known, and short of the whole id space: a build-plane step may meet an id it cannot map. */
  public boolean narrowIdRange() {
    return idRange != null && idRange < FULL_ID_RANGE;
  }
}

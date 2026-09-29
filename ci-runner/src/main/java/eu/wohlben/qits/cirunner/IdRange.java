package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.Capabilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How many uids and gids this process's user namespace maps — {@link Capabilities#idRange()}.
 *
 * <p>Read from this process's own {@code uid_map} and {@code gid_map}, because the runner runs in its
 * docker daemon's user namespace (a container of rootless docker or of an unprivileged LXC is
 * inside the same map the daemon is), and it is the daemon's map that decides whether buildkit can
 * unpack a layer owning uid 1001380000 (qits-556). Each line is {@code inside outside count}; the
 * range is the sum of the counts, and the smaller of the two maps, since either one short is enough
 * for an {@code lchown} to fail.
 *
 * <p>{@code null} when a map cannot be read or parsed — not a guess: the field's absence tells
 * qits-ci "unknown", which it allows, and a wrong number would either strand a good runner or hand a
 * bad one a build. The directory is a parameter so the suite can hand it a fake {@code /proc/self}.
 */
final class IdRange {

  /** This process's own maps. */
  static final Path PROC_SELF = Path.of("/proc/self");

  private IdRange() {}

  static Long read(Path procSelf) {
    Long uids = total(procSelf.resolve("uid_map"));
    Long gids = total(procSelf.resolve("gid_map"));
    return uids == null || gids == null ? null : Math.min(uids, gids);
  }

  /**
   * The operator's fix, in the words the warning uses — the runner cannot widen its own map: inside
   * the namespace nothing can write the host's {@code /etc/subuid}, and an LXC idmap lives on the
   * LXC host.
   */
  static String narrowWarning(long range) {
    return "ci-runner's user namespace maps only "
        + range
        + " uids/gids (a full host maps "
        + Capabilities.FULL_ID_RANGE
        + "), so buildkit cannot unpack an image layer that owns a file above that id and qits-ci"
        + " will not hand this runner a build step. Widen the range on this host: for rootless"
        + " docker, the rootless user's entries in /etc/subuid and /etc/subgid (then restart its"
        + " dockerd); for an unprivileged LXC, the container's lxc.idmap on the LXC host.";
  }

  private static Long total(Path map) {
    String text;
    try {
      text = Files.readString(map);
    } catch (IOException | RuntimeException e) {
      return null;
    }
    long total = 0;
    boolean any = false;
    for (String line : text.split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      String[] fields = trimmed.split("\\s+");
      if (fields.length != 3) {
        return null;
      }
      try {
        total += Long.parseLong(fields[2]);
      } catch (NumberFormatException e) {
        return null;
      }
      any = true;
    }
    return any ? total : null;
  }
}

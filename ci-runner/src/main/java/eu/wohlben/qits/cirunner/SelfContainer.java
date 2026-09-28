package eu.wohlben.qits.cirunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which container this process is running in — the id the runner names itself by when it inspects
 * its own parameters for a successor and when it takes its own restart policy away.
 *
 * <p><b>{@code /proc/self/mountinfo} first, {@code HOSTNAME} second, and not {@code
 * /proc/self/cgroup}.</b> Measured in a container on this estate's own hosts: on cgroup v2 with the
 * private cgroup namespace docker gives every container by default, {@code /proc/self/cgroup} reads
 * {@code 0::/} and names nothing. What does name the container is the three files docker bind-mounts
 * into it from {@code /var/lib/docker/containers/<64-hex id>/} ({@code hostname}, {@code hosts},
 * {@code resolv.conf}), which show up in mountinfo with the full id in the path on cgroup v1 and v2
 * alike. {@code HOSTNAME} is the 12-hex short id docker sets by default — also accepted by every
 * docker command — but only until somebody runs the container with {@code --hostname}, so it is the
 * fallback, and only when it looks like an id.
 */
public final class SelfContainer {

  private static final Pattern MOUNTED_ID =
      Pattern.compile("/containers/([0-9a-f]{64})/(?:hostname|hosts|resolv\\.conf)\\s");

  private static final Pattern SHORT_ID = Pattern.compile("[0-9a-f]{12,64}");

  private SelfContainer() {}

  /** This process's container id, or empty when it is not in a docker container. */
  public static Optional<String> detect() {
    String mountinfo;
    try {
      mountinfo = Files.readString(Path.of("/proc/self/mountinfo"), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      mountinfo = "";
    }
    return idFrom(mountinfo, System.getenv("HOSTNAME"));
  }

  /** The pure half of {@link #detect}, for the suite. */
  static Optional<String> idFrom(String mountinfo, String hostname) {
    Matcher matcher = MOUNTED_ID.matcher(mountinfo == null ? "" : mountinfo);
    if (matcher.find()) {
      return Optional.of(matcher.group(1));
    }
    if (hostname != null && SHORT_ID.matcher(hostname.strip()).matches()) {
      return Optional.of(hostname.strip());
    }
    return Optional.empty();
  }

  /**
   * Is {@code candidate} (as {@code docker ps} prints it, 12 hex) the container {@code self} names
   * (12 to 64 hex)? Either may be a prefix of the other.
   */
  static boolean same(String self, String candidate) {
    if (self == null || candidate == null || self.isEmpty() || candidate.isEmpty()) {
      return false;
    }
    return self.startsWith(candidate) || candidate.startsWith(self);
  }
}

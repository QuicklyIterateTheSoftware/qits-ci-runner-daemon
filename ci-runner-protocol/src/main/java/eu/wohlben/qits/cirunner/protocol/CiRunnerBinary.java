package eu.wohlben.qits.cirunner.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * <b>What this jar was released with</b>: the {@code qits-ci-runner} version of the release that
 * published this artifact — {@code CiDaemonBinary}'s twin, and for the same reason.
 *
 * <p>A runner is a container on its host, started from the image {@code <registry
 * host>/}{@value #IMAGE_REPOSITORY}{@code :<version>} that this repository's release pushes beside
 * this jar. qits-ci renders the install script a person pastes on a runner host from that image
 * reference, and names the same reference in the {@link Upgrade} it sends a runner of any other
 * version. Taking the version from this constant makes the pin a line in qits-ci's pom that its own
 * release request gated, rather than whatever happened to be newest in the registry the moment
 * somebody pressed copy — and it is the same release as the wire contract qits-ci compiles against,
 * so the host and the runner it hands out cannot disagree about the protocol by a deployment act.
 *
 * <p><b>The value is this module's {@code ${project.version}}, resolved at build time</b> into
 * {@code ci-runner-binary.properties} beside this class. A build from a working tree therefore names
 * a {@code -SNAPSHOT} or the previous release, which is honest: nothing has been published for the
 * tree in hand.
 *
 * <p><b>The contract this names is an image tag</b>, so a blank or unfiltered value is refused
 * loudly here rather than defaulted: a fallback would compose a {@code docker pull} that fails on a
 * machine nobody on the platform can see.
 */
public final class CiRunnerBinary {

  /** The resource the build filters {@code ${project.version}} into, beside this class. */
  private static final String RESOURCE = "ci-runner-binary.properties";

  /**
   * The runner's name: the image's last path segment, the binary's file name inside it, and the
   * prefix of every runner container's name on a host.
   */
  public static final String RUNNER_NAME = "qits-ci-runner";

  /**
   * The image's repository under the registry host — the coordinate this repository's release
   * declares in {@code artifacts:} and pushes, and the one qits-ci prefixes with its registry's
   * public host to render the install script and every {@link Upgrade}.
   */
  public static final String IMAGE_REPOSITORY = "qits/" + RUNNER_NAME;

  /** The released version: the tag of the {@code qits-ci-runner} image published beside this jar. */
  public static final String VERSION = readVersion();

  private static String readVersion() {
    Properties p = new Properties();
    try (InputStream in = CiRunnerBinary.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        // A jar of this module with the resource missing is a broken build, and an install script
        // that silently pulled "" or "latest" is the failure this class exists to remove.
        throw new IllegalStateException(
            RESOURCE + " is not on the classpath beside " + CiRunnerBinary.class.getName());
      }
      p.load(in);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + RESOURCE, e);
    }
    String version = p.getProperty("version", "");
    if (version.isBlank() || version.startsWith("$")) {
      // `$` catches resource filtering switched off, leaving the literal `${project.version}`.
      throw new IllegalStateException(RESOURCE + " carries no resolved version: '" + version + "'");
    }
    return version;
  }

  private CiRunnerBinary() {}
}

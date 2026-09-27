package eu.wohlben.qits.cirunner.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * <b>What this jar was released with</b>: the {@code qits-ci-runner} binary version of the release
 * that published this artifact — {@code CiDaemonBinary}'s twin, and for the same reason.
 *
 * <p>qits-ci renders the install script a person pastes on a runner host, and that script downloads
 * the binary from qits-artifacts by name and version. Taking the version from this constant makes
 * the pin a line in qits-ci's pom that its own release request gated, rather than whatever happened
 * to be newest in the store the moment somebody pressed copy — and it is the same release as the
 * wire contract qits-ci compiles against, so the host and the binary it hands out cannot disagree
 * about the protocol by a deployment act.
 *
 * <p><b>The value is this module's {@code ${project.version}}, resolved at build time</b> into
 * {@code ci-runner-binary.properties} beside this class. A build from a working tree therefore names
 * a {@code -SNAPSHOT} or the previous release, which is honest: nothing has been published for the
 * tree in hand.
 *
 * <p><b>The contract this names is a URL</b> — {@code …/artifacts/daemons/qits-ci-runner/<version>}
 * — so a blank or unfiltered value is refused loudly here rather than defaulted: a fallback would
 * compose a download that 404s on a machine nobody on the platform can see.
 */
public final class CiRunnerBinary {

  /** The resource the build filters {@code ${project.version}} into, beside this class. */
  private static final String RESOURCE = "ci-runner-binary.properties";

  /**
   * The binary's artifact name in qits-artifacts' {@code daemons} store — the coordinate the
   * release pipeline publishes under and the path segment the install script downloads from.
   */
  public static final String RUNNER_NAME = "qits-ci-runner";

  /** The released version: the {@code qits-ci-runner} binary published beside this jar. */
  public static final String VERSION = readVersion();

  private static String readVersion() {
    Properties p = new Properties();
    try (InputStream in = CiRunnerBinary.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        // A jar of this module with the resource missing is a broken build, and an install script
        // that silently downloaded "" or "latest" is the failure this class exists to remove.
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

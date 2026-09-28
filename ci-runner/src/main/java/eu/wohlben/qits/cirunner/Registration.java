package eu.wohlben.qits.cirunner;

import eu.wohlben.qits.cirunner.protocol.Capabilities;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Turn a one-time registration token into this runner's own idp client, once, and keep it.
 *
 * <p>{@code client.json} in the state directory is the whole of the runner's identity after the
 * first start: mode {@code 0600}, written atomically, read on every start. When it is there the
 * register door is never called and the token is never sent again — qits-ci deletes it on first use
 * anyway, so a second presentation could only fail.
 *
 * <p><b>Rotation is a new token, and a new token re-registers.</b> The file records a SHA-256 of the
 * token that produced it. On a start where the environment carries a token whose hash differs, the
 * operator pasted a fresh install line (the CI UI's "replace registration token"), and the runner
 * registers with it and replaces the file; the same token as before is the same env file after a
 * reboot, and changes nothing. That is what lets the install script rewrite only the env file and
 * restart the unit, with no knowledge of the state directory.
 */
public final class Registration {

  private static final Logger LOG = Logger.getLogger(Registration.class);

  /** The file under the state directory. */
  public static final String CLIENT_FILE = "client.json";

  /** How much of a refusal's body the journal quotes. */
  static final int MAX_QUOTED = 500;

  /** A registration that cannot proceed, with the exit code and the one line to print. */
  public static final class Failed extends Exception {
    private final int exitCode;

    public Failed(int exitCode, String message) {
      super(message);
      this.exitCode = exitCode;
    }

    public int exitCode() {
      return exitCode;
    }
  }

  private final Http http;
  private final long timeoutMillis;

  public Registration(Http http, long timeoutMillis) {
    this.http = http;
    this.timeoutMillis = timeoutMillis;
  }

  /** The client to dial with: the stored one, or a fresh registration's. Blocks; call off-loop. */
  public ClientCredentials ensure(RunnerEnv env, Capabilities capabilities) throws Failed {
    Path file = env.stateDir().resolve(CLIENT_FILE);
    ClientCredentials stored = read(file);
    String token = env.registrationToken();
    if (stored != null && (token.isEmpty() || hash(token).equals(stored.registeredWith()))) {
      return stored;
    }
    if (token.isEmpty()) {
      throw new Failed(
          ExitCode.MISCONFIGURED,
          "QITS_CI_RUNNER_REGISTRATION_TOKEN is not set, and this runner is not registered yet ("
              + file
              + " does not exist)");
    }
    if (stored != null) {
      LOG.info("ci-runner was given a new registration token; registering again");
    }
    ClientCredentials registered = register(env, capabilities, token);
    write(file, registered);
    LOG.infof("ci-runner registered as %s", env.runnerId());
    return registered;
  }

  private ClientCredentials register(RunnerEnv env, Capabilities capabilities, String token)
      throws Failed {
    String url = env.url() + "/ci/api/runners/" + env.runnerId() + "/register";
    JsonObject body =
        new JsonObject()
            .put(
                "capabilities",
                new JsonObject()
                    .put("docker", capabilities.docker())
                    .put("arch", capabilities.arch())
                    .put("os", capabilities.os())
                    .put("labels", new JsonObject(Map.copyOf(capabilities.labels()))));
    Http.Response response;
    try {
      response =
          http.post(
                  url,
                  Map.of(
                      "Authorization", "Bearer " + token,
                      "Content-Type", "application/json",
                      "Accept", "application/json"),
                  body.encode())
              .toCompletionStage()
              .toCompletableFuture()
              .get(timeoutMillis + 5_000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failed(ExitCode.REGISTRATION_UNREACHABLE, "registration was interrupted");
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new Failed(
          ExitCode.REGISTRATION_UNREACHABLE,
          "could not reach " + url + ": " + cause.getMessage());
    }
    int status = response.status();
    if (status >= 400 && status < 500) {
      throw new Failed(
          ExitCode.REGISTRATION_REFUSED,
          "qits-ci refused the registration (" + status + "): \"" + quoted(response.body()) + "\"");
    }
    if (status != 200) {
      throw new Failed(
          ExitCode.REGISTRATION_UNREACHABLE,
          "qits-ci answered " + status + " at " + url + ": \"" + quoted(response.body()) + "\"");
    }
    try {
      return ClientCredentials.fromJson(new JsonObject(response.body()), hash(token));
    } catch (RuntimeException e) {
      throw new Failed(
          ExitCode.STATE_UNUSABLE, "qits-ci's registration answer is not a client: " + e.getMessage());
    }
  }

  private static ClientCredentials read(Path file) throws Failed {
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (NoSuchFileException absent) {
      return null;
    } catch (IOException e) {
      throw new Failed(ExitCode.STATE_UNUSABLE, "cannot read " + file + ": " + e.getMessage());
    }
    try {
      return ClientCredentials.fromJson(new JsonObject(text), null);
    } catch (RuntimeException e) {
      throw new Failed(
          ExitCode.STATE_UNUSABLE,
          file + " is not a usable client (" + e.getMessage() + "); delete it and start again"
              + " with a fresh registration token");
    }
  }

  /**
   * Write the client with {@code 0600} from its first byte: the temp file is created with the mode
   * rather than chmod-ed after the write, so the secret is never readable by anyone else even for
   * the instant in between. The move is atomic, so a crash leaves the old file or the new, never half
   * of one.
   */
  private static void write(Path file, ClientCredentials client) throws Failed {
    Set<PosixFilePermission> owner = PosixFilePermissions.fromString("rw-------");
    Path temp = file.resolveSibling(CLIENT_FILE + ".tmp");
    try {
      Files.createDirectories(file.getParent());
      Files.deleteIfExists(temp);
      Files.createFile(temp, PosixFilePermissions.asFileAttribute(owner));
      Files.writeString(temp, client.toJson().encodePrettily(), StandardCharsets.UTF_8);
      try {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
      // Belt: a umask cannot widen a mode set at create, but a pre-existing file moved over could
      // have been anything.
      Files.setPosixFilePermissions(file, owner);
    } catch (IOException | UnsupportedOperationException e) {
      throw new Failed(ExitCode.STATE_UNUSABLE, "cannot write " + file + ": " + e.getMessage());
    }
  }

  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from this JVM", e);
    }
  }

  private static String quoted(String body) {
    String oneLine = body == null ? "" : body.strip().replaceAll("\\s+", " ");
    return oneLine.length() <= MAX_QUOTED ? oneLine : oneLine.substring(0, MAX_QUOTED) + "…";
  }
}

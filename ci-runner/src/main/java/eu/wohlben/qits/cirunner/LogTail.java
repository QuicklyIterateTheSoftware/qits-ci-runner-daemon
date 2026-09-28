package eu.wohlben.qits.cirunner;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * A step container's last words, read before its removal destroys them: {@code docker inspect} for
 * how it ended, {@code docker logs --tail} for what it said, both streams merged in the order they
 * were written.
 *
 * <p><b>Why the runner reads them at all.</b> A step's output reaches qits-ci through the step's
 * own daemon. A step whose daemon never dialled back — a DNS name the runner's host cannot resolve,
 * a certificate it does not trust — has no other record, and on a runner off the platform's host the
 * container's log was readable only by a person at that machine, until the reap removed it. So the
 * tail rides the {@code Reaped} for the host to keep, and a container the runner removes on its own
 * account (a sweep, a cancel) has it written to the runner's own log instead.
 *
 * <p><b>Bounded twice, newest kept.</b> Docker bounds the lines ({@link #MAX_LINES}); this bounds the
 * bytes ({@link #MAX_BYTES}), dropping whole lines from the front, because the line that explains a
 * failure is the last one. The capture is twice the bound, so a line cut by the capture's ring is
 * always among those dropped.
 *
 * <p><b>Redacted before it leaves.</b> A step's environment carries credentials and a step that
 * echoes one would otherwise hand it to every reader of the run. {@link #redact} takes out what looks
 * like one — a platform token, a bearer, a JWT, a {@code password=}-shaped
 * assignment, a URL's userinfo — which is a belt, not a guarantee: the step is still the one that
 * must not print its secrets.
 *
 * <p><b>Never an obstacle to the removal.</b> A docker that cannot answer is a null tail and a
 * reap that goes ahead; what this reads is diagnosis, never a precondition.
 */
public final class LogTail {

  private static final Logger LOG = Logger.getLogger(LogTail.class);

  /** Lines asked of {@code docker logs}. */
  static final int MAX_LINES = 200;

  /** Bytes a tail may carry, before its state line. */
  static final int MAX_BYTES = 32 * 1024;

  /** Bytes captured from docker: twice the bound, so the ring's cut falls in what is dropped. */
  static final int CAPTURE_BYTES = 2 * MAX_BYTES;

  /** The line a tail that lost its head begins with. */
  static final String DROPPED = "[earlier output dropped]";

  /** What {@code {{.State.Status}} {{.State.ExitCode}}} answers, and nothing else. */
  private static final Pattern STATE = Pattern.compile("([a-z]{1,16}) (-?[0-9]{1,5})");

  private static final String REDACTED = "[redacted]";

  /**
   * Each shape a credential takes in a log line, with the part to keep as group 1. In this order:
   * the shapes that carry their own key first, the bare platform token last, so {@code Bearer
   * qits_tok_…} becomes one {@code Bearer [redacted]} rather than a redaction inside a redaction.
   */
  private static final List<Pattern> SECRETS =
      List.of(
          // an Authorization header's value, however it was printed
          Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"),
          // a JWT anywhere, header.payload.signature
          Pattern.compile("()eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]*"),
          // password=…, client_secret: …, "access_token":"…", REGISTRATION_TOKEN=…, "auth":"…"
          Pattern.compile(
              "(?i)(\\b(?:[a-z0-9_.-]*(?:password|passwd|secret|token)|auth)[\"']?\\s*[=:]\\s*[\"']?)"
                  + "[^\\s\"'&,;]+"),
          // https://user:secret@host
          Pattern.compile("(://[^/\\s:@]+:)[^/\\s@]+(?=@)"),
          // a platform token wherever else it stands: its prefix says what it was
          Pattern.compile("(qits_tok_)[A-Za-z0-9_.~+/=-]+"));

  private final Docker docker;
  private final String dockerBinary;

  public LogTail(Docker docker, String dockerBinary) {
    this.docker = docker;
    this.dockerBinary = dockerBinary;
  }

  /**
   * The tail of {@code name}'s output, led by a {@code [container exited <code>]} line when it had
   * exited (and {@code [container <status>]} otherwise), or null when docker could not produce its
   * logs. Never throws.
   */
  public String read(String name) {
    try {
      Docker.Result state = docker.run(RunnerArgv.exitState(dockerBinary, name));
      Docker.Result logs = docker.runMerged(RunnerArgv.logs(dockerBinary, name), CAPTURE_BYTES);
      if (!logs.ok()) {
        LOG.debugf("ci-runner could not read %s's output: %s", name, logs.detail());
        return null;
      }
      String header = state.ok() ? stateLine(state.stdout()) : null;
      String body = bound(redact(logs.stdout()));
      return header == null ? body : header + "\n" + body;
    } catch (IllegalArgumentException notOurs) {
      // A name outside the charset was never one of ours; the caller refuses it too.
      return null;
    } catch (RuntimeException unexpected) {
      LOG.debugf("ci-runner could not read %s's output: %s", name, unexpected.getMessage());
      return null;
    }
  }

  /** {@code exited 1} → {@code [container exited 1]}; {@code running 0} → {@code [container running]}. */
  static String stateLine(String inspected) {
    Matcher m = STATE.matcher(inspected == null ? "" : inspected.strip());
    if (!m.matches()) {
      return null;
    }
    return m.group(1).equals("exited")
        ? "[container exited " + m.group(2) + "]"
        : "[container " + m.group(1) + "]";
  }

  /** {@code text} with everything credential-shaped replaced by {@value #REDACTED}. */
  static String redact(String text) {
    if (text == null || text.isEmpty()) {
      return text == null ? "" : text;
    }
    String redacted = text;
    for (Pattern secret : SECRETS) {
      redacted =
          secret.matcher(redacted).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + REDACTED));
    }
    return redacted;
  }

  /**
   * At most {@link #MAX_BYTES} of UTF-8 from the end of {@code text}, starting at a line; {@link
   * #DROPPED} first when anything was cut. One line longer than the bound keeps its own tail.
   */
  static String bound(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= MAX_BYTES) {
      return text;
    }
    int from = bytes.length - MAX_BYTES;
    int start = -1;
    for (int i = from - 1; i < bytes.length - 1; i++) {
      if (bytes[i] == '\n') {
        start = i + 1;
        break;
      }
    }
    if (start < 0) {
      start = from;
      while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
        start++; // never begin inside a multi-byte character
      }
    }
    return DROPPED
        + "\n"
        + new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
  }
}

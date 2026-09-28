package eu.wohlben.qits.cirunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A shell script that answers like docker, written into a test's own temp directory — the
 * qits-system-service suite's stand-in, made per test so two suites never share a call log.
 *
 * <p>It records every invocation (one record per call, one field per argv element, separated by
 * ASCII unit/record separators so an argument holding a newline — the builder's bootstrap does —
 * survives the round trip) and answers from files: the answer key is the first argument, or the
 * first two for {@code image}/{@code network}/{@code volume}, and {@code answers/<key>.code},
 * {@code .out} and {@code .err} are the exit code, stdout and stderr. No file is exit 0 and silence.
 * {@code answers/<key>.sleep} makes the call hang that many seconds, for the deadline.
 *
 * <p>The production {@link Docker#forking} runs it, so the seam under test is the real one.
 */
final class FakeDocker {

  private static final String SCRIPT =
      """
      #!/bin/sh
      dir=$(dirname "$0")
      { for a in "$@"; do printf '%s\\037' "$a"; done; printf '\\036'; } >> "$dir/calls.log"
      if [ "$1" = "--config" ]; then
        { stat -c '%a' "$2" "$2/config.json" | tr '\\n' ' '; printf '\\037'; \
          cat "$2/config.json"; printf '\\036'; } >> "$dir/configs.log"
        shift 2
      fi
      key="$1"
      case "$1" in image|network|volume) key="$1-$2";; esac
      a="$dir/answers/$key"
      [ -f "$a.sleep" ] && sleep "$(cat "$a.sleep")"
      [ -f "$a.out" ] && cat "$a.out"
      [ -f "$a.err" ] && cat "$a.err" >&2
      code=0
      [ -f "$a.code" ] && code=$(cat "$a.code")
      exit "$code"
      """;

  final Path dir;
  final String binary;

  FakeDocker(Path dir) throws IOException {
    this.dir = dir;
    Files.createDirectories(dir.resolve("answers"));
    Path script = dir.resolve("docker");
    Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    this.binary = script.toString();
  }

  Docker docker(long timeoutSeconds) {
    return Docker.forking(timeoutSeconds);
  }

  /** Make {@code key} answer with this exit code, stdout and stderr. */
  FakeDocker answer(String key, int code, String out, String err) throws IOException {
    Path answers = dir.resolve("answers");
    Files.writeString(answers.resolve(key + ".code"), String.valueOf(code));
    Files.writeString(answers.resolve(key + ".out"), out == null ? "" : out);
    Files.writeString(answers.resolve(key + ".err"), err == null ? "" : err);
    return this;
  }

  FakeDocker hang(String key, int seconds) throws IOException {
    Files.writeString(dir.resolve("answers").resolve(key + ".sleep"), String.valueOf(seconds));
    return this;
  }

  /** Every call, each as its argv WITHOUT the binary path — so assertions read like docker. */
  List<List<String>> calls() throws IOException {
    Path log = dir.resolve("calls.log");
    if (!Files.exists(log)) {
      return List.of();
    }
    List<List<String>> calls = new ArrayList<>();
    for (String record : Files.readString(log, StandardCharsets.UTF_8).split("\u001e")) {
      if (record.isEmpty()) {
        continue;
      }
      String[] fields = record.split("\u001f", -1);
      calls.add(List.of(Arrays.copyOf(fields, fields.length - 1)));
    }
    return calls;
  }

  /**
   * The calls whose first argument is {@code verb}, a leading {@code --config <dir>} skipped — so a
   * pull under a launch's login is still a pull.
   */
  List<List<String>> calls(String verb) throws IOException {
    return calls().stream()
        .map(FakeDocker::withoutConfig)
        .filter(c -> !c.isEmpty() && c.getFirst().equals(verb))
        .toList();
  }

  /** {@code call} without a leading {@code --config <dir>}. */
  static List<String> withoutConfig(List<String> call) {
    return call.size() >= 2 && call.getFirst().equals("--config")
        ? call.subList(2, call.size())
        : call;
  }

  /**
   * What each {@code --config} call found in its directory at the moment docker ran, in call order:
   * {@code [<dir mode> <file mode>, <config.json>]}. Read by the script itself, because the launcher
   * deletes the directory as soon as the pull is over.
   */
  List<List<String>> configs() throws IOException {
    Path log = dir.resolve("configs.log");
    if (!Files.exists(log)) {
      return List.of();
    }
    List<List<String>> configs = new ArrayList<>();
    for (String record : Files.readString(log, StandardCharsets.UTF_8).split("\u001e")) {
      if (record.isEmpty()) {
        continue;
      }
      String[] fields = record.split("\u001f", -1);
      configs.add(List.of(fields[0].strip(), fields[1]));
    }
    return configs;
  }
}

package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The pure halves of a tail: its state line, its redaction and its bound. */
class LogTailTest {

  @Test
  void theStateLineNamesTheExitCodeOnlyForAnExitedContainer() {
    assertEquals("[container exited 137]", LogTail.stateLine("exited 137\n"));
    assertEquals("[container running]", LogTail.stateLine("running 0"));
    assertEquals("[container created]", LogTail.stateLine("created 0"));
    assertNull(LogTail.stateLine("{\"Id\":\"…\"}"), "anything else is no state line");
    assertNull(LogTail.stateLine(""));
  }

  @Test
  void everyCredentialShapeIsRedactedAndItsKeyKept() {
    assertEquals(
        "Authorization: Bearer [redacted]",
        LogTail.redact("Authorization: Bearer eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl"));
    assertEquals("token qits_tok_[redacted] used", LogTail.redact("token qits_tok_AbC-123_x used"));
    assertEquals("password=[redacted] user=bob", LogTail.redact("password=hunter2 user=bob"));
    assertEquals(
        "{\"access_token\":\"[redacted]\",\"expires_in\":300}",
        LogTail.redact("{\"access_token\":\"eyJabc.def\",\"expires_in\":300}"));
    assertEquals(
        "QITS_CI_RUNNER_REGISTRATION_TOKEN=[redacted]",
        LogTail.redact("QITS_CI_RUNNER_REGISTRATION_TOKEN=qits_tok_abc"));
    assertEquals("client_secret: [redacted]", LogTail.redact("client_secret: s3cr3t"));
    assertEquals("{\"auth\":\"[redacted]\"}", LogTail.redact("{\"auth\":\"dG9rZW46eHl6\"}"));
    assertEquals(
        "git clone https://oauth2:[redacted]@githost/x.git",
        LogTail.redact("git clone https://oauth2:abc.def@githost/x.git"));
    assertEquals("jwt [redacted] seen", LogTail.redact("jwt eyJhbGciOiJ.eyJzdWIiOiJ.sig_ seen"));
  }

  @Test
  void ordinaryOutputIsLeftAlone() {
    String text = "BUILD SUCCESS\nTests run: 12, Failures: 0\nconnecting to ci.qits.example.eu:443\n";
    assertEquals(text, LogTail.redact(text));
  }

  @Test
  void aShortTailIsKeptWhole() {
    assertEquals("a\nb\n", LogTail.bound("a\nb\n"));
  }

  @Test
  void aLongTailDropsWholeLinesFromTheFront() {
    String line = "y".repeat(99) + "\n";
    String text = "first\n" + line.repeat(400);
    String bounded = LogTail.bound(text);
    assertTrue(bounded.startsWith(LogTail.DROPPED + "\n" + line), bounded.substring(0, 40));
    assertTrue(bounded.getBytes(StandardCharsets.UTF_8).length <= LogTail.MAX_BYTES + 32);
    assertTrue(bounded.endsWith(line));
  }

  @Test
  void oneLineLongerThanTheBoundKeepsItsOwnTailWithoutSplittingACharacter() {
    String text = "ä".repeat(LogTail.MAX_BYTES); // two bytes each
    String bounded = LogTail.bound(text);
    String kept = bounded.substring(LogTail.DROPPED.length() + 1);
    assertTrue(kept.chars().allMatch(c -> c == 'ä'), "no replacement character at the cut");
    assertTrue(kept.getBytes(StandardCharsets.UTF_8).length <= LogTail.MAX_BYTES);
  }
}

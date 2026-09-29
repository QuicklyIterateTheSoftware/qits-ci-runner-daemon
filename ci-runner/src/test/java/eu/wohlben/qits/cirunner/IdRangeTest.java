package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cirunner.protocol.Capabilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The id range a runner advertises, read from a fake {@code /proc/self}. */
class IdRangeTest {

  @TempDir Path proc;

  private void maps(String uid, String gid) throws IOException {
    Files.writeString(proc.resolve("uid_map"), uid);
    Files.writeString(proc.resolve("gid_map"), gid);
  }

  @Test
  void aHostOutsideAnyUserNamespaceMapsTheWholeIdSpace() throws IOException {
    maps("         0          0 4294967295\n", "         0          0 4294967295\n");
    assertEquals(Long.valueOf(Capabilities.FULL_ID_RANGE), IdRange.read(proc));
    assertFalse(Main.capabilities(proc, true).narrowIdRange());
  }

  @Test
  void rootlessDockerMapsSixtyFiveThousandAndThatIsNarrow() throws IOException {
    // rootless dockerd: its own uid as 0, then the user's /etc/subuid range.
    maps("0 1000 1\n1 100000 65536\n", "0 1000 1\n1 100000 65536\n");
    assertEquals(Long.valueOf(65537L), IdRange.read(proc));
    Capabilities caps = Main.capabilities(proc, true);
    assertEquals(Long.valueOf(65537L), caps.idRange());
    assertTrue(caps.narrowIdRange());
  }

  @Test
  void theRangeIsTheSumOfEveryLineAndTheSmallerOfTheTwoMaps() throws IOException {
    maps("0 100000 65536\n65536 300000 1000000\n", "0 100000 65536\n");
    assertEquals(Long.valueOf(65536L), IdRange.read(proc));
  }

  @Test
  void anUnreadableOrGarbledMapIsUnknownNotAGuess() throws IOException {
    assertNull(IdRange.read(proc), "no maps at all");
    maps("0 0 4294967295\n", "");
    assertNull(IdRange.read(proc), "an empty gid_map");
    maps("0 0 4294967295\n", "0 0 lots\n");
    assertNull(IdRange.read(proc), "a count that is not a number");
    maps("0 0\n", "0 0 4294967295\n");
    assertNull(IdRange.read(proc), "a line short of its count");
    assertNull(Main.capabilities(proc, true).idRange());
  }

  @Test
  void theWarningNamesTheRangeAndTheOperatorsFix() {
    String warning = IdRange.narrowWarning(65536);
    assertTrue(warning.contains("65536"), warning);
    assertTrue(warning.contains("/etc/subuid") && warning.contains("/etc/subgid"), warning);
    assertTrue(warning.contains("lxc.idmap"), warning);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void thisProcessReadsItsOwnMaps() {
    assertTrue(IdRange.read(IdRange.PROC_SELF) > 0);
  }
}

package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Which container the runner is in — see {@link SelfContainer} for what was measured. */
class SelfContainerTest {

  private static final String ID = "137a7ce88caaea8690032dd0fdf96f0905ae2ed8fb03a7914d62b79deb58faa8";

  /** Three lines of a real container's mountinfo, on a cgroup v2 host. */
  private static final String MOUNTINFO =
      "1504 1460 8:1 /var/lib/docker/containers/"
          + ID
          + "/resolv.conf /etc/resolv.conf rw,relatime - ext4 /dev/sda1 rw\n"
          + "1505 1460 8:1 /var/lib/docker/containers/"
          + ID
          + "/hostname /etc/hostname rw,relatime - ext4 /dev/sda1 rw\n"
          + "1506 1460 8:1 /var/lib/docker/volumes/qits-ci-runner-state-r1/_data /var/lib/qits-ci-runner"
          + " rw,relatime - ext4 /dev/sda1 rw\n";

  @Test
  void theFullIdComesFromTheFilesDockerMountsIntoTheContainer() {
    assertEquals(Optional.of(ID), SelfContainer.idFrom(MOUNTINFO, "someone-set-a-hostname"));
  }

  @Test
  void aDefaultHostnameIsTheShortIdWhenMountinfoNamesNone() {
    assertEquals(Optional.of("137a7ce88caa"), SelfContainer.idFrom("", "137a7ce88caa"));
  }

  @Test
  void outsideAContainerThereIsNoSelf() {
    assertEquals(Optional.empty(), SelfContainer.idFrom("25 1 8:1 / / rw - ext4 /dev/sda1 rw\n", "buildhost"));
    assertEquals(Optional.empty(), SelfContainer.idFrom(null, null));
  }

  @Test
  void aShortAndAFullIdOfOneContainerAreTheSame() {
    assertTrue(SelfContainer.same(ID, "137a7ce88caa"));
    assertTrue(SelfContainer.same("137a7ce88caa", ID));
    assertFalse(SelfContainer.same(ID, "aaaaaaaaaaaa"));
    assertFalse(SelfContainer.same("", ID));
  }
}

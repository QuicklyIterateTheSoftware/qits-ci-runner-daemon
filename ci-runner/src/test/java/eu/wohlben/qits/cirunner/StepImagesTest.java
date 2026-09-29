package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class StepImagesTest {

  @TempDir Path dir;

  private static final long WEEK = TimeUnit.DAYS.toMillis(7);

  private final AtomicLong now = new AtomicLong();

  private StepImages recorded(Path file) {
    StepImages images = new StepImages(file, now::get);
    now.set(0);
    images.used("stale:1");
    images.used("in-use:1");
    images.used("gone:1");
    now.set(TimeUnit.DAYS.toMillis(8) - TimeUnit.HOURS.toMillis(1));
    images.used("fresh:1");
    now.set(TimeUnit.DAYS.toMillis(8));
    return images;
  }

  @Test
  void theSweepRemovesOnlyStaleUnusedRecordedImagesThenPrunesDanglingOnesWithoutA()
      throws Exception {
    FakeDocker fake =
        new FakeDocker(dir.resolve("docker"))
            .answerFor("image-inspect", "gone:1", 1, "", "No such image: gone:1")
            .answerFor("ps", "ancestor=in-use:1", 0, "cafebabe0000\n", "")
            .answer("image-prune", 0, "Total reclaimed space: 0B\n", "");
    Path file = dir.resolve("state").resolve(StepImages.FILE);
    StepImages images = recorded(file);

    assertEquals(1, images.sweep(fake.docker(10), fake.binary, WEEK));

    List<List<String>> imageCalls = fake.calls("image");
    List<List<String>> removals = imageCalls.stream().filter(c -> c.get(1).equals("rm")).toList();
    assertEquals(List.of(List.of("image", "rm", "stale:1")), removals, imageCalls::toString);
    assertEquals(List.of("image", "prune", "-f"), fake.calls().getLast(), "the prune comes last");
    assertTrue(
        fake.calls().stream().noneMatch(c -> c.contains("-a") && c.contains("prune")),
        "never image prune -a");
    assertFalse(
        fake.calls().stream().anyMatch(c -> c.contains("fresh:1")), "a fresh image is not asked");
    // What was removed, or had gone already, is no longer the runner's; the rest is, on disk too.
    assertEquals(Map.of("in-use:1", 0L, "fresh:1", now.get() - 3_600_000L), images.record());
    assertEquals(images.record(), new StepImages(file, now::get).record());
  }

  @Test
  void anImageIsLeftAloneWhileALaunchIsInFlight() throws Exception {
    FakeDocker fake = new FakeDocker(dir.resolve("docker"));
    StepImages images = recorded(null);
    Lock launching = images.launching();
    launching.lock();
    try {
      assertEquals(0, images.sweep(fake.docker(10), fake.binary, WEEK));
    } finally {
      launching.unlock();
    }
    assertEquals(List.of(), fake.calls(), "neither a removal nor the prune");
    assertEquals(4, images.record().size());
  }

  @Test
  void anUnreadableRecordStartsEmptyRatherThanFailing() throws Exception {
    Path file = dir.resolve(StepImages.FILE);
    Files.writeString(file, "not json");
    assertEquals(Map.of(), new StepImages(file, now::get).record());
  }
}

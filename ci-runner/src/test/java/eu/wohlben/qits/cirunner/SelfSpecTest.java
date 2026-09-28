package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a successor inherits from {@code docker inspect} of the running container. */
class SelfSpecTest {

  private static final String IMAGE =
      """
      {"env":["PATH=/usr/bin","DOCKER_VERSION=29.8.1"],"labels":{"org.opencontainers.image.version":"29.8.1"}}
      """;

  @Test
  void theImagesOwnEnvironmentAndLabelsAreNotCarried() {
    SelfSpec spec =
        SelfSpec.parse(
            """
            {"id":"abc","image":"sha256:old",
             "env":["PATH=/usr/bin","DOCKER_VERSION=29.8.1","QITS_CI_RUNNER_ID=r1","HTTPS_PROXY=http://p:3128"],
             "labels":{"org.opencontainers.image.version":"29.8.1","qits.ci.runner.process":"r1"},
             "binds":["/var/run/docker.sock:/var/run/docker.sock"],"mounts":null,
             "restart":{"Name":"unless-stopped","MaximumRetryCount":0},"network":"default"}
            """,
            IMAGE);
    assertEquals(Map.of("QITS_CI_RUNNER_ID", "r1", "HTTPS_PROXY", "http://p:3128"), spec.env());
    assertEquals(Map.of("qits.ci.runner.process", "r1"), spec.labels());
    assertEquals(List.of("/var/run/docker.sock:/var/run/docker.sock"), spec.binds());
    assertEquals(List.of(), spec.mounts());
    assertEquals("unless-stopped", spec.restart());
    assertEquals("sha256:old", spec.image());
  }

  @Test
  void aVariableTheOperatorOverrodeIsCarriedEvenWhenTheImageSetsItToo() {
    SelfSpec spec =
        SelfSpec.parse(
            """
            {"env":["PATH=/opt/tools:/usr/bin"],"labels":null,"binds":null,"mounts":null,
             "restart":null,"network":"qits-net"}
            """,
            IMAGE);
    assertEquals(Map.of("PATH", "/opt/tools:/usr/bin"), spec.env());
    assertEquals("no", spec.restart(), "docker reports no policy as an empty name");
    assertEquals("qits-net", spec.network());
  }

  @Test
  void mountFlagsAndRetryCountsAreSpelledTheWayTheCliTakesThem() {
    SelfSpec spec =
        SelfSpec.parse(
            """
            {"env":[],"labels":{},"binds":[],
             "mounts":[{"Type":"volume","Source":"extra","Target":"/extra","ReadOnly":true},
                       {"Type":"bind","Source":"/srv/ca.pem","Target":"/ca.pem"}],
             "restart":{"Name":"on-failure","MaximumRetryCount":5},"network":""}
            """,
            IMAGE);
    assertEquals(
        List.of(
            "type=volume,source=extra,target=/extra,readonly",
            "type=bind,source=/srv/ca.pem,target=/ca.pem"),
        spec.mounts());
    assertEquals("on-failure:5", spec.restart());
  }

  @Test
  void anAnswerThatIsNotJsonIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> SelfSpec.parse("Error: No such object", IMAGE));
  }
}

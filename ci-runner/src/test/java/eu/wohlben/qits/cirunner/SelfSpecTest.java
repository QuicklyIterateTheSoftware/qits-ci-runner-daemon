package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a successor inherits from {@code docker inspect} of the running container — the whole
 * document {@code docker inspect}/{@code docker image inspect} answer with no {@code --format}, one
 * element per id in a JSON array, exactly as {@link RunnerArgv#inspectSelf} and {@link
 * RunnerArgv#imageConfig} run it.
 */
class SelfSpecTest {

  private static final String IMAGE =
      """
      [{"Id":"sha256:old","Config":{"Env":["PATH=/usr/bin","DOCKER_VERSION=29.8.1"],
       "Labels":{"org.opencontainers.image.version":"29.8.1"}}}]
      """;

  /**
   * The live shape that broke the {@code --format} template: a container started with {@code -v}
   * binds only has no {@code HostConfig.Mounts} key at all — not an empty array, absent — because
   * that is what docker answers when nothing was ever mounted with {@code --mount}. A template
   * referencing it refused to render at all; parsing must instead read it as empty.
   */
  @Test
  void aContainerWithBindsOnlyAndNoMountsKeyAtAllParsesToNoMounts() {
    SelfSpec spec =
        SelfSpec.parse(
            """
            [{"Id":"abc123","Image":"sha256:old","Name":"/qits-ci-runner-r1-2026.928.133438",
             "Config":{"Env":["PATH=/usr/bin","DOCKER_VERSION=29.8.1","QITS_CI_RUNNER_ID=r1"],
                       "Labels":{"org.opencontainers.image.version":"29.8.1",
                                 "qits.ci.runner.process":"r1"}},
             "HostConfig":{"Binds":["/var/run/docker.sock:/var/run/docker.sock",
                                     "qits-ci-runner-state-r1:/var/lib/qits-ci-runner"],
                            "RestartPolicy":{"Name":"unless-stopped","MaximumRetryCount":0},
                            "NetworkMode":"default"}}]
            """,
            IMAGE);
    assertEquals(Map.of("QITS_CI_RUNNER_ID", "r1"), spec.env());
    assertEquals(Map.of("qits.ci.runner.process", "r1"), spec.labels());
    assertEquals(
        List.of(
            "/var/run/docker.sock:/var/run/docker.sock",
            "qits-ci-runner-state-r1:/var/lib/qits-ci-runner"),
        spec.binds());
    assertEquals(List.of(), spec.mounts(), "an absent HostConfig.Mounts is no mounts, not a refusal");
    assertEquals("unless-stopped", spec.restart());
    assertEquals("sha256:old", spec.image());
    assertEquals("abc123", spec.id());
  }

  /** A container that carries both {@code -v} binds and {@code --mount}s together. */
  @Test
  void aContainerWithBothBindsAndMountsCarriesBoth() {
    SelfSpec spec =
        SelfSpec.parse(
            """
            [{"Id":"abc","Image":"sha256:old",
             "Config":{"Env":[],"Labels":{}},
             "HostConfig":{"Binds":["/var/run/docker.sock:/var/run/docker.sock"],
                            "Mounts":[{"Type":"volume","Source":"extra","Target":"/extra",
                                       "ReadOnly":true},
                                      {"Type":"bind","Source":"/srv/ca.pem","Target":"/ca.pem"}],
                            "RestartPolicy":{"Name":"on-failure","MaximumRetryCount":5},
                            "NetworkMode":""}}]
            """,
            IMAGE);
    assertEquals(List.of("/var/run/docker.sock:/var/run/docker.sock"), spec.binds());
    assertEquals(
        List.of(
            "type=volume,source=extra,target=/extra,readonly",
            "type=bind,source=/srv/ca.pem,target=/ca.pem"),
        spec.mounts());
    assertEquals("on-failure:5", spec.restart());
  }

  @Test
  void theImagesOwnEnvironmentAndLabelsAreNotCarried() {
    SelfSpec spec =
        SelfSpec.parse(
            """
            [{"Id":"abc","Image":"sha256:old",
             "Config":{"Env":["PATH=/usr/bin","DOCKER_VERSION=29.8.1","QITS_CI_RUNNER_ID=r1",
                               "HTTPS_PROXY=http://p:3128"],
                       "Labels":{"org.opencontainers.image.version":"29.8.1",
                                 "qits.ci.runner.process":"r1"}},
             "HostConfig":{"Binds":["/var/run/docker.sock:/var/run/docker.sock"],
                            "RestartPolicy":{"Name":"unless-stopped","MaximumRetryCount":0},
                            "NetworkMode":"default"}}]
            """,
            IMAGE);
    assertEquals(Map.of("QITS_CI_RUNNER_ID", "r1", "HTTPS_PROXY", "http://p:3128"), spec.env());
    assertEquals(Map.of("qits.ci.runner.process", "r1"), spec.labels());
    assertEquals(List.of("/var/run/docker.sock:/var/run/docker.sock"), spec.binds());
    assertEquals(List.of(), spec.mounts());
    assertEquals("unless-stopped", spec.restart());
    assertEquals("sha256:old", spec.image());
  }

  /**
   * Missing {@code Config}, {@code HostConfig}, {@code Labels} and {@code Env} altogether — not
   * merely empty — are every one a default, never a refusal: this is the same defensiveness the
   * missing-{@code Mounts} case exercises, applied to every other optional key {@code docker
   * inspect} can omit.
   */
  @Test
  void aMissingConfigOrHostConfigOrLabelsOrEnvIsHandled() {
    SelfSpec spec = SelfSpec.parse("""
        [{"Id":"abc","Image":"sha256:old"}]
        """, IMAGE);
    assertEquals(Map.of(), spec.env());
    assertEquals(Map.of(), spec.labels());
    assertEquals(List.of(), spec.binds());
    assertEquals(List.of(), spec.mounts());
    assertEquals("no", spec.restart(), "docker reports no policy as an empty name");
    assertEquals("", spec.network());
  }

  @Test
  void aVariableTheOperatorOverrodeIsCarriedEvenWhenTheImageSetsItToo() {
    SelfSpec spec =
        SelfSpec.parse(
            """
            [{"Id":"abc","Image":"sha256:old",
             "Config":{"Env":["PATH=/opt/tools:/usr/bin"]},
             "HostConfig":{"NetworkMode":"qits-net"}}]
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
            [{"Id":"abc","Image":"sha256:old",
             "Config":{"Env":[],"Labels":{}},
             "HostConfig":{"Binds":[],
                            "Mounts":[{"Type":"volume","Source":"extra","Target":"/extra",
                                       "ReadOnly":true},
                                      {"Type":"bind","Source":"/srv/ca.pem","Target":"/ca.pem"}],
                            "RestartPolicy":{"Name":"on-failure","MaximumRetryCount":5},
                            "NetworkMode":""}}]
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
    assertThrows(
        IllegalArgumentException.class, () -> SelfSpec.parse("Error: No such object", IMAGE));
  }

  @Test
  void anEmptyArrayIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> SelfSpec.parse("[]", IMAGE));
  }

  @Test
  void theImageIdIsReadFromTheContainerAnswerAloneBeforeTheImageAnswerExists() {
    assertEquals(
        "sha256:old",
        SelfSpec.imageIdOf(
            """
            [{"Id":"abc","Image":"sha256:old"}]
            """));
  }
}

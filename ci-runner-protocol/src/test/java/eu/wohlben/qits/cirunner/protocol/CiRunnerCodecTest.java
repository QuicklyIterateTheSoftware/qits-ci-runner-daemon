package eu.wohlben.qits.cirunner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wire contract's framework-free guard: every message survives {@code encode → decode}
 * unchanged, an unknown type is a typed error, and unknown fields are ignored. qits-ci and the
 * runner only bridge the map to their JSON library, so this covers the mapping both depend on.
 */
class CiRunnerCodecTest {

  private static CiRunnerMessage roundTrip(CiRunnerMessage message) {
    return CiRunnerCodec.decode(CiRunnerCodec.encode(message));
  }

  private static WorkloadSpec fullSpec() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_CI_DAEMON_ID", "d-1");
    env.put("BUILDKIT_HOST", "");
    return new WorkloadSpec(
        "registry.example/qits/build-images/ci-base:latest",
        List.of("/bin/sh"),
        List.of("-c", "set -e\necho hi"),
        env,
        Map.of("qits.ci.run", "run-1"),
        "qits-net",
        List.of("host.docker.internal:host-gateway"),
        "1000",
        true,
        true,
        true,
        "4g",
        "4g",
        4096L,
        "4",
        1000,
        "qits-ci-run-1-abc-0",
        true);
  }

  @Test
  void everyMessageRoundTrips() {
    List<CiRunnerMessage> all =
        List.of(
            new Hello(
                "2026.927.1",
                CiRunnerProtocol.CAPABILITY_VERSION,
                new Capabilities(true, "amd64", "linux", Map.of("site", "home"))),
            new Reserve(),
            new Launched("run-1", 2, "0123abcd"),
            new LaunchFailed("run-1", 2, "pull access denied"),
            new Reaped("run-1", 2),
            new Heartbeat(),
            new Ack(CiRunnerProtocol.CAPABILITY_VERSION, 3),
            new Backlog(7),
            new Take("run-1", "qits-ci-service", "main", "0123456789abcdef"),
            new Nothing(),
            new Launch("run-1", 0, fullSpec()),
            new Launch("run-1", 1, WorkloadSpec.of("alpine:3", "qits-ci-x-1")),
            new Reap("run-1", 2, "qits-ci-run-1-abc-2"),
            new Cancel("run-1"),
            new Released("run-1"));
    for (CiRunnerMessage message : all) {
      assertEquals(message, roundTrip(message), () -> "did not round-trip: " + message);
    }
  }

  @Test
  void everyPermittedTypeIsCoveredByTheRoundTrip() {
    // A new record added to the sealed set without a case above would still compile the codec's
    // switch only if it had an arm; this pins that the test list grows with it.
    assertEquals(14, CiRunnerMessage.class.getPermittedSubclasses().length);
  }

  @Test
  void theDiscriminatorIsTheProtocolConstant() {
    assertEquals(
        CiRunnerProtocol.Type.LAUNCH_FAILED,
        CiRunnerCodec.encode(new LaunchFailed("r", 0, "x")).get(CiRunnerProtocol.Field.TYPE));
    assertEquals(
        CiRunnerProtocol.Type.RELEASED,
        CiRunnerCodec.encode(new Released("r")).get(CiRunnerProtocol.Field.TYPE));
  }

  @Test
  void anUnknownTypeIsATypedDecodeErrorNamingTheType() {
    CiRunnerDecodeException e =
        assertThrows(
            CiRunnerDecodeException.class,
            () -> CiRunnerCodec.decode(Map.of("type", "teleport", "runId", "r")));
    assertEquals(CiRunnerDecodeException.Reason.UNKNOWN_TYPE, e.reason());
    assertEquals("teleport", e.type());
  }

  @Test
  void aMissingTypeIsATypedDecodeError() {
    CiRunnerDecodeException e =
        assertThrows(CiRunnerDecodeException.class, () -> CiRunnerCodec.decode(Map.of("runId", "r")));
    assertEquals(CiRunnerDecodeException.Reason.MISSING_TYPE, e.reason());
    assertNull(e.type());
  }

  @Test
  void aNestedFieldOfTheWrongShapeIsMalformedRatherThanAClassCast() {
    CiRunnerDecodeException e =
        assertThrows(
            CiRunnerDecodeException.class,
            () ->
                CiRunnerCodec.decode(
                    Map.of("type", "launch", "runId", "r", "workloadSpec", "not an object")));
    assertEquals(CiRunnerDecodeException.Reason.MALFORMED, e.reason());
  }

  @Test
  void unknownFieldsAreIgnoredAtEveryLevel() {
    Map<String, Object> spec = new LinkedHashMap<>(CiRunnerCodec.encode(new Launch("r", 1, fullSpec())));
    @SuppressWarnings("unchecked")
    Map<String, Object> nested =
        new LinkedHashMap<>((Map<String, Object>) spec.get(CiRunnerProtocol.Field.WORKLOAD_SPEC));
    nested.put("gpu", "please");
    spec.put(CiRunnerProtocol.Field.WORKLOAD_SPEC, nested);
    spec.put("priority", 9);
    assertEquals(new Launch("r", 1, fullSpec()), CiRunnerCodec.decode(spec));

    Map<String, Object> ack = new LinkedHashMap<>(CiRunnerCodec.encode(new Ack(1, 2)));
    ack.put("motd", "hello from a newer host");
    assertEquals(new Ack(1, 2), CiRunnerCodec.decode(ack));
  }

  @Test
  void numbersDecodeWhicheverBoxTheJsonLayerChose() {
    // Vert.x decodes JSON integers as Long, Jackson as Integer; both must read the same.
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("type", "ack");
    map.put("capabilityVersion", 1L);
    map.put("slots", 2L);
    assertEquals(new Ack(1, 2), CiRunnerCodec.decode(map));
  }

  @Test
  void aNestedObjectMayArriveAsAnIterableOfEntriesTheWayAVertxJsonObjectIs() {
    Map<String, Object> caps = new LinkedHashMap<>();
    caps.put("docker", true);
    caps.put("arch", "amd64");
    caps.put("os", "linux");
    Iterable<Map.Entry<String, Object>> asEntries = caps.entrySet();
    Map<String, Object> hello = new LinkedHashMap<>();
    hello.put("type", "hello");
    hello.put("runnerVersion", "v");
    hello.put("capabilityVersion", 1);
    hello.put("capabilities", asEntries);
    assertEquals(
        new Hello("v", 1, new Capabilities(true, "amd64", "linux", Map.of())),
        CiRunnerCodec.decode(hello));
    // And an entry that is not an entry is malformed, not silently dropped.
    Map<String, Object> bad = new LinkedHashMap<>(hello);
    bad.put("capabilities", List.of(new AbstractMap.SimpleEntry<>("docker", true), "junk"));
    assertEquals(
        CiRunnerDecodeException.Reason.MALFORMED,
        assertThrows(CiRunnerDecodeException.class, () -> CiRunnerCodec.decode(bad)).reason());
  }

  @Test
  void absentOptionalSpecFieldsDecodeToTheirEmptyForms() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("type", "launch");
    map.put("runId", "r");
    map.put("stepIndex", 0);
    map.put("workloadSpec", Map.of("image", "alpine:3", "name", "n"));
    assertEquals(new Launch("r", 0, WorkloadSpec.of("alpine:3", "n")), CiRunnerCodec.decode(map));
  }
}

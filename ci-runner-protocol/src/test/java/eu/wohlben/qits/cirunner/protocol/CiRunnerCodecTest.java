package eu.wohlben.qits.cirunner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                2,
                new Capabilities(true, "amd64", "linux", Map.of("site", "home"))),
            new Hello(
                "2026.927.1",
                CiRunnerProtocol.CAPABILITY_VERSION,
                2,
                new Capabilities(true, "amd64", "linux", Map.of()),
                List.of("run-1", "run-2")),
            new Reserve(),
            new Launched("run-1", 2, "0123abcd"),
            new LaunchFailed("run-1", 2, "pull access denied"),
            new Reaped("run-1", 2),
            new Reaped("run-1", 2, "[container exited 1]\nerror: no route to ci\n"),
            new Heartbeat(),
            new Ack(CiRunnerProtocol.CAPABILITY_VERSION, 3),
            new Ack(
                CiRunnerProtocol.CAPABILITY_VERSION,
                3,
                Map.of(
                    "mirror.dev.localhost:8080", "mirror.qits.wohlben.eu",
                    "registry.dev.localhost:8080", "registry.qits.wohlben.eu")),
            new Ack(CiRunnerProtocol.CAPABILITY_VERSION, 3, Map.of()),
            new Ack(CiRunnerProtocol.CAPABILITY_VERSION, 3, null, List.of("run-1")),
            new Ack(CiRunnerProtocol.CAPABILITY_VERSION, 3, null, List.of()),
            new Backlog(7),
            new Take("run-1", "qits-ci-service", "main", "0123456789abcdef"),
            new Nothing(),
            new Launch("run-1", 0, fullSpec()),
            new Launch("run-1", 1, WorkloadSpec.of("alpine:3", "qits-ci-x-1")),
            new Reap("run-1", 2, "qits-ci-run-1-abc-2"),
            new Cancel("run-1"),
            new Released("run-1"),
            new Upgrade(
                "2026.928.120000",
                "registry.qits.example.eu/qits/qits-ci-runner:2026.928.120000",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
            new Upgrade(
                "2026.928.120000",
                "registry.qits.example.eu/qits/qits-ci-runner@sha256:"
                    + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                null),
            new Retire("superseded by 2026.928.120000"),
            Retire.deleted("deleted by admin"),
            new Quarantined("3 runner-caused failures in a row", "2026-09-28T12:00:00Z"),
            new Reinstated("admin"),
            new Reinstated("healthcheck"));
    for (CiRunnerMessage message : all) {
      assertEquals(message, roundTrip(message), () -> "did not round-trip: " + message);
    }
  }

  @Test
  void everyPermittedTypeIsCoveredByTheRoundTrip() {
    // A new record added to the sealed set without a case above would still compile the codec's
    // switch only if it had an arm; this pins that the test list grows with it.
    assertEquals(18, CiRunnerMessage.class.getPermittedSubclasses().length);
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

  /**
   * The self-update frames are how a runner of ANY older version learns it must update, so their
   * wire shape is pinned here as literals — not through the constants a rename would move with it.
   */
  @Test
  void theSelfUpdateFramesKeepTheirFrozenWireShape() {
    Map<String, Object> upgrade = new LinkedHashMap<>();
    upgrade.put("type", "upgrade");
    upgrade.put("version", "2");
    upgrade.put("image", "r/qits/qits-ci-runner:2");
    upgrade.put("sha256", "ab");
    assertEquals(upgrade, CiRunnerCodec.encode(new Upgrade("2", "r/qits/qits-ci-runner:2", "ab")));
    assertEquals(new Upgrade("2", "r/qits/qits-ci-runner:2", "ab"), CiRunnerCodec.decode(upgrade));

    assertEquals(
        Map.of("type", "retire", "reason", "superseded"),
        CiRunnerCodec.encode(new Retire("superseded")));
    assertEquals(
        new Retire("superseded"), CiRunnerCodec.decode(Map.of("type", "retire", "reason", "superseded")));

    assertEquals(
        Retire.Kind.SUPERSEDED,
        ((Retire) CiRunnerCodec.decode(Map.of("type", "retire", "reason", "superseded"))).kind(),
        "a Retire without a kind — every host before kinds — is a self-update's");

    assertEquals(
        "1.0",
        CiRunnerCodec.encode(new Hello("1.0", 1, 1, null)).get("runnerVersion"),
        "Hello.runnerVersion is what the host compares with its pin");
  }

  /**
   * A deleted runner's {@code Retire} adds {@code kind}, and only that: {@code reason} stays where an
   * older runner reads it, and a kind this binary does not know is read as the safe one — a
   * self-update's, which never removes a state volume.
   */
  @Test
  void aDeletedRetireAddsItsKindAndAnUnknownKindReadsAsSuperseded() {
    Map<String, Object> deleted = new LinkedHashMap<>();
    deleted.put("type", "retire");
    deleted.put("reason", "deleted");
    deleted.put("kind", "DELETED");
    assertEquals(deleted, CiRunnerCodec.encode(Retire.deleted("deleted")));
    assertEquals(Retire.deleted("deleted"), CiRunnerCodec.decode(deleted));
    assertEquals(
        new Retire("x", Retire.Kind.SUPERSEDED),
        CiRunnerCodec.decode(Map.of("type", "retire", "reason", "x", "kind", "SOMETHING_NEWER")));
    assertEquals(Retire.Kind.SUPERSEDED, new Retire("x", null).kind());
  }

  /**
   * The quarantine frames are additive, unlike the self-update ones: an older runner just drops
   * them as an unknown type. Still worth pinning their wire shape by literal, the way the self-update
   * frames are, since {@code CiRunnerProtocol}'s constants are what a rename would move with them —
   * this catches a rename that forgot the wire is what a runner in the field already understands.
   */
  @Test
  void theQuarantineFramesHaveTheDocumentedWireShape() {
    Map<String, Object> quarantined = new LinkedHashMap<>();
    quarantined.put("type", "quarantined");
    quarantined.put("reason", "3 runner-caused failures in a row");
    quarantined.put("since", "2026-09-28T12:00:00Z");
    assertEquals(
        quarantined,
        CiRunnerCodec.encode(
            new Quarantined("3 runner-caused failures in a row", "2026-09-28T12:00:00Z")));
    assertEquals(
        new Quarantined("3 runner-caused failures in a row", "2026-09-28T12:00:00Z"),
        CiRunnerCodec.decode(quarantined));

    assertEquals(
        Map.of("type", "reinstated", "by", "admin"),
        CiRunnerCodec.encode(new Reinstated("admin")));
    assertEquals(new Reinstated("admin"), CiRunnerCodec.decode(Map.of("type", "reinstated", "by", "admin")));
  }

  /**
   * An {@code Ack} from before qits-ci sent registry mirrors at all carries no such key, and that is
   * not the same as "sent, empty": both this repo's {@link #nullableStringMap} decoder and the
   * runner's merge logic (see {@code BuildPlane.withAckMirrors}) treat absent as "leave the builder's
   * env-configured mirrors alone".
   */
  @Test
  void anAckWithNoRegistryMirrorsKeyDecodesToANullMap() {
    assertEquals(
        new Ack(1, 2, null),
        CiRunnerCodec.decode(Map.of("type", "ack", "capabilityVersion", 1, "slots", 2)));
  }

  /**
   * The runs carried across a lost socket are added fields, and each side's older peer is the case
   * that matters: a first connection's {@code Hello} carries no key at all (the frame it always was),
   * and an {@code Ack} with no {@code adoptedRuns} key — every host before the field — is {@code
   * null}, "said nothing", which is not the same as "adopted none".
   */
  @Test
  void heldAndAdoptedRunsAreAbsentUnlessSaidAndAnAbsentAdoptionIsNull() {
    assertFalse(
        CiRunnerCodec.encode(new Hello("v", 1, 1, null))
            .containsKey(CiRunnerProtocol.Field.HELD_RUNS));
    assertEquals(
        List.of("run-1"),
        CiRunnerCodec.encode(new Hello("v", 1, 1, null, List.of("run-1")))
            .get(CiRunnerProtocol.Field.HELD_RUNS));
    assertFalse(CiRunnerCodec.encode(new Ack(1, 2)).containsKey(CiRunnerProtocol.Field.ADOPTED_RUNS));
    assertNull(
        ((Ack) CiRunnerCodec.decode(Map.of("type", "ack", "capabilityVersion", 1, "slots", 2)))
            .adoptedRuns());
    assertEquals(
        List.of(),
        ((Ack)
                CiRunnerCodec.decode(
                    Map.of("type", "ack", "capabilityVersion", 1, "slots", 2, "adoptedRuns", List.of())))
            .adoptedRuns());
  }

  @Test
  void anAckWithRegistryMirrorsKeepsTheHostsOrderOnTheWire() {
    Map<String, String> mirrors = new LinkedHashMap<>();
    mirrors.put("mirror.dev.localhost:8080", "mirror.qits.wohlben.eu");
    mirrors.put("registry.dev.localhost:8080", "registry.qits.wohlben.eu");
    Ack ack = new Ack(1, 2, mirrors);
    Map<String, Object> encoded = CiRunnerCodec.encode(ack);
    @SuppressWarnings("unchecked")
    Map<String, Object> onWire = (Map<String, Object>) encoded.get(CiRunnerProtocol.Field.REGISTRY_MIRRORS);
    assertEquals(List.copyOf(mirrors.keySet()), List.copyOf(onWire.keySet()));
    assertEquals(ack, CiRunnerCodec.decode(encoded));
  }

  @Test
  void anUpgradeWithoutAChecksumDecodesToANullOneAndAFutureFieldIsIgnored() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("type", "upgrade");
    map.put("version", "2");
    map.put("image", "r/qits/qits-ci-runner:2");
    map.put("signature", "a field a later host may add");
    assertEquals(new Upgrade("2", "r/qits/qits-ci-runner:2", null), CiRunnerCodec.decode(map));
  }

  /**
   * What a runner released before these frames existed does with one: its codec has no arm for the
   * type, so it throws the typed UNKNOWN_TYPE — which that runner's ControlSocket catches, logs and
   * drops, staying connected (RunnerMainTest pins that half). Shown here with a type this codec does
   * not know, which is exactly the position an older codec is in with "upgrade".
   */
  @Test
  void aFrameAnOlderCodecDoesNotKnowIsTheTypedUnknownTypeItsReceiverDrops() {
    CiRunnerDecodeException e =
        assertThrows(
            CiRunnerDecodeException.class,
            () ->
                CiRunnerCodec.decode(
                    Map.of("type", "upgradeV2", "version", "2", "image", "r/qits/qits-ci-runner:2")));
    assertEquals(CiRunnerDecodeException.Reason.UNKNOWN_TYPE, e.reason());
  }

  /**
   * {@code logTail} arrived after the first runners were installed: their {@code reaped} frames
   * carry no such key and must still decode, to a null tail, and the key a host reads is pinned as a
   * literal so a rename of the constant cannot quietly move it.
   */
  @Test
  void aReapedFromARunnerOlderThanTheLogTailDecodesToANullOne() {
    assertEquals(
        new Reaped("run-1", 2, null),
        CiRunnerCodec.decode(Map.of("type", "reaped", "runId", "run-1", "stepIndex", 2)));
    assertEquals(new Reaped("run-1", 2, null), new Reaped("run-1", 2));
    assertEquals(
        "exited",
        CiRunnerCodec.encode(new Reaped("run-1", 2, "exited")).get("logTail"),
        "the host reads the tail under this key");
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
        new Hello("v", 1, 0, new Capabilities(true, "amd64", "linux", Map.of())),
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

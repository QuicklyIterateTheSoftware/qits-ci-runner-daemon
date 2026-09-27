package eu.wohlben.qits.cirunner.protocol;

import eu.wohlben.qits.cirunner.protocol.CiRunnerDecodeException.Reason;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol.Field;
import eu.wohlben.qits.cirunner.protocol.CiRunnerProtocol.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place the runner control-socket messages become (and un-become) a {@code Map<String,
 * Object>} — the wire's lowest common denominator, as {@code CiDaemonCodec} has it. Framework-free
 * on purpose: qits-ci bridges the map to and from JSON with its Jackson {@code ObjectMapper}, the
 * runner with a Vert.x {@code JsonObject}, so neither side reimplements the field mapping.
 *
 * <p><b>Tolerant of unknown fields, in both directions.</b> Decoding reads the keys it knows and
 * ignores the rest, so a peer that grew a field does not break one that has not — adding a field is
 * not a capability bump, only changing the meaning of one is. <b>An unknown {@code type} is not
 * tolerated</b>: it throws {@link CiRunnerDecodeException} with {@link Reason#UNKNOWN_TYPE}, because
 * a message nobody here understands cannot be turned into a message; what the receiver does with
 * that (both sides drop and log) is its decision, not the codec's.
 *
 * <p>Numbers are read through {@link Number}, so it does not matter whether the JSON layer decoded
 * an {@code int} as {@code Integer} (Jackson) or {@code Long} (Vert.x). Nested objects are read as a
 * {@code Map} or as any {@code Iterable} of {@code Map.Entry} — which is what a Vert.x {@code
 * JsonObject} nested inside another one is — and lists as any {@code Iterable}, which covers a
 * {@code JsonArray}; so a caller hands over {@code jsonObject.getMap()} and nothing deeper needs
 * unwrapping.
 */
public final class CiRunnerCodec {

  private CiRunnerCodec() {}

  /** Flatten a message to its wire map, including the {@code "type"} discriminator. */
  public static Map<String, Object> encode(CiRunnerMessage message) {
    Map<String, Object> map = new LinkedHashMap<>();
    switch (message) {
      case Hello m -> {
        map.put(Field.TYPE, Type.HELLO);
        map.put(Field.RUNNER_VERSION, m.runnerVersion());
        map.put(Field.CAPABILITY_VERSION, m.capabilityVersion());
        map.put(Field.SLOTS, m.slots());
        map.put(Field.CAPABILITIES, m.capabilities() == null ? null : capabilities(m.capabilities()));
      }
      case Reserve _ -> map.put(Field.TYPE, Type.RESERVE);
      case Launched m -> {
        map.put(Field.TYPE, Type.LAUNCHED);
        map.put(Field.RUN_ID, m.runId());
        map.put(Field.STEP_INDEX, m.stepIndex());
        map.put(Field.CONTAINER_ID, m.containerId());
      }
      case LaunchFailed m -> {
        map.put(Field.TYPE, Type.LAUNCH_FAILED);
        map.put(Field.RUN_ID, m.runId());
        map.put(Field.STEP_INDEX, m.stepIndex());
        map.put(Field.DETAIL, m.detail());
      }
      case Reaped m -> {
        map.put(Field.TYPE, Type.REAPED);
        map.put(Field.RUN_ID, m.runId());
        map.put(Field.STEP_INDEX, m.stepIndex());
      }
      case Heartbeat _ -> map.put(Field.TYPE, Type.HEARTBEAT);
      case Ack m -> {
        map.put(Field.TYPE, Type.ACK);
        map.put(Field.CAPABILITY_VERSION, m.capabilityVersion());
        map.put(Field.SLOTS, m.slots());
      }
      case Backlog m -> {
        map.put(Field.TYPE, Type.BACKLOG);
        map.put(Field.QUEUED, m.queued());
      }
      case Take m -> {
        map.put(Field.TYPE, Type.TAKE);
        map.put(Field.RUN_ID, m.runId());
        map.put(Field.REPO_NAME, m.repoName());
        map.put(Field.BRANCH, m.branch());
        map.put(Field.SHA, m.sha());
      }
      case Nothing _ -> map.put(Field.TYPE, Type.NOTHING);
      case Launch m -> {
        map.put(Field.TYPE, Type.LAUNCH);
        map.put(Field.RUN_ID, m.runId());
        map.put(Field.STEP_INDEX, m.stepIndex());
        map.put(Field.WORKLOAD_SPEC, m.workloadSpec() == null ? null : spec(m.workloadSpec()));
      }
      case Reap m -> {
        map.put(Field.TYPE, Type.REAP);
        map.put(Field.RUN_ID, m.runId());
        map.put(Field.STEP_INDEX, m.stepIndex());
        map.put(Field.CONTAINER_NAME, m.containerName());
      }
      case Cancel m -> {
        map.put(Field.TYPE, Type.CANCEL);
        map.put(Field.RUN_ID, m.runId());
      }
      case Released m -> {
        map.put(Field.TYPE, Type.RELEASED);
        map.put(Field.RUN_ID, m.runId());
      }
    }
    return map;
  }

  /**
   * Rebuild a message from its wire map, dispatching on the {@code "type"} discriminator.
   *
   * @throws CiRunnerDecodeException with {@link Reason#MISSING_TYPE}, {@link Reason#UNKNOWN_TYPE}
   *     or {@link Reason#MALFORMED}
   */
  public static CiRunnerMessage decode(Map<String, Object> map) {
    String type = map == null ? null : str(map, Field.TYPE);
    if (type == null) {
      throw new CiRunnerDecodeException(
          Reason.MISSING_TYPE, null, "ci-runner message has no '" + Field.TYPE + "' field");
    }
    try {
      return switch (type) {
        case Type.HELLO ->
            new Hello(
                str(map, Field.RUNNER_VERSION),
                intVal(map, Field.CAPABILITY_VERSION),
                intVal(map, Field.SLOTS),
                capabilities(object(map, Field.CAPABILITIES)));
        case Type.RESERVE -> new Reserve();
        case Type.LAUNCHED ->
            new Launched(
                str(map, Field.RUN_ID),
                intVal(map, Field.STEP_INDEX),
                str(map, Field.CONTAINER_ID));
        case Type.LAUNCH_FAILED ->
            new LaunchFailed(
                str(map, Field.RUN_ID), intVal(map, Field.STEP_INDEX), str(map, Field.DETAIL));
        case Type.REAPED -> new Reaped(str(map, Field.RUN_ID), intVal(map, Field.STEP_INDEX));
        case Type.HEARTBEAT -> new Heartbeat();
        case Type.ACK -> new Ack(intVal(map, Field.CAPABILITY_VERSION), intVal(map, Field.SLOTS));
        case Type.BACKLOG -> new Backlog(intVal(map, Field.QUEUED));
        case Type.TAKE ->
            new Take(
                str(map, Field.RUN_ID),
                str(map, Field.REPO_NAME),
                str(map, Field.BRANCH),
                str(map, Field.SHA));
        case Type.NOTHING -> new Nothing();
        case Type.LAUNCH ->
            new Launch(
                str(map, Field.RUN_ID),
                intVal(map, Field.STEP_INDEX),
                spec(object(map, Field.WORKLOAD_SPEC)));
        case Type.REAP ->
            new Reap(
                str(map, Field.RUN_ID),
                intVal(map, Field.STEP_INDEX),
                str(map, Field.CONTAINER_NAME));
        case Type.CANCEL -> new Cancel(str(map, Field.RUN_ID));
        case Type.RELEASED -> new Released(str(map, Field.RUN_ID));
        default ->
            throw new CiRunnerDecodeException(
                Reason.UNKNOWN_TYPE, type, "unknown ci-runner message type: " + type);
      };
    } catch (CiRunnerDecodeException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CiRunnerDecodeException(
          Reason.MALFORMED, type, "malformed ci-runner '" + type + "' message: " + e.getMessage());
    }
  }

  // --- nested objects -----------------------------------------------------------------------------

  private static Map<String, Object> capabilities(Capabilities c) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(Field.DOCKER, c.docker());
    map.put(Field.ARCH, c.arch());
    map.put(Field.OS, c.os());
    map.put(Field.LABELS, new LinkedHashMap<>(c.labels()));
    return map;
  }

  private static Capabilities capabilities(Map<String, Object> map) {
    if (map == null) {
      return null;
    }
    return new Capabilities(
        boolVal(map, Field.DOCKER),
        str(map, Field.ARCH),
        str(map, Field.OS),
        stringMap(map, Field.LABELS));
  }

  private static Map<String, Object> spec(WorkloadSpec s) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(Field.IMAGE, s.image());
    map.put(Field.ENTRYPOINT, new ArrayList<>(s.entrypoint()));
    map.put(Field.ARGS, new ArrayList<>(s.args()));
    map.put(Field.ENV, new LinkedHashMap<>(s.env()));
    map.put(Field.LABELS, new LinkedHashMap<>(s.labels()));
    map.put(Field.NETWORK, s.network());
    map.put(Field.EXTRA_HOSTS, new ArrayList<>(s.extraHosts()));
    map.put(Field.USER, s.user());
    map.put(Field.HOST_DOCKER_SOCKET, s.hostDockerSocket());
    map.put(Field.CAP_DROP_ALL, s.capDropAll());
    map.put(Field.NO_NEW_PRIVILEGES, s.noNewPrivileges());
    map.put(Field.MEMORY, s.memory());
    map.put(Field.MEMORY_SWAP, s.memorySwap());
    map.put(Field.PIDS_LIMIT, s.pidsLimit());
    map.put(Field.CPUS, s.cpus());
    map.put(Field.OOM_SCORE_ADJ, s.oomScoreAdj());
    map.put(Field.NAME, s.name());
    map.put(Field.BUILD_PLANE, s.buildPlane());
    return map;
  }

  private static WorkloadSpec spec(Map<String, Object> map) {
    if (map == null) {
      return null;
    }
    Number pids = number(map, Field.PIDS_LIMIT);
    Number oom = number(map, Field.OOM_SCORE_ADJ);
    return new WorkloadSpec(
        str(map, Field.IMAGE),
        stringList(map, Field.ENTRYPOINT),
        stringList(map, Field.ARGS),
        stringMap(map, Field.ENV),
        stringMap(map, Field.LABELS),
        str(map, Field.NETWORK),
        stringList(map, Field.EXTRA_HOSTS),
        str(map, Field.USER),
        boolVal(map, Field.HOST_DOCKER_SOCKET),
        boolVal(map, Field.CAP_DROP_ALL),
        boolVal(map, Field.NO_NEW_PRIVILEGES),
        str(map, Field.MEMORY),
        str(map, Field.MEMORY_SWAP),
        pids == null ? null : pids.longValue(),
        str(map, Field.CPUS),
        oom == null ? null : oom.intValue(),
        str(map, Field.NAME),
        boolVal(map, Field.BUILD_PLANE));
  }

  // --- scalar readers -----------------------------------------------------------------------------

  private static String str(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? null : value.toString();
  }

  private static int intVal(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof Number number ? number.intValue() : 0;
  }

  private static Number number(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number;
    }
    throw new IllegalArgumentException("'" + key + "' is not a number");
  }

  private static boolean boolVal(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof Boolean bool && bool;
  }

  /**
   * A nested object, as a {@code Map} or as an {@code Iterable} of entries (a Vert.x {@code
   * JsonObject} nested in another one). Anything else under the key is a malformed frame.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Map<?, ?> nested) {
      return (Map<String, Object>) nested;
    }
    if (value instanceof Iterable<?> entries) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Object entry : entries) {
        if (!(entry instanceof Map.Entry<?, ?> e)) {
          throw new IllegalArgumentException("'" + key + "' is not an object");
        }
        copy.put(String.valueOf(e.getKey()), e.getValue());
      }
      return copy;
    }
    throw new IllegalArgumentException("'" + key + "' is not an object");
  }

  private static List<String> stringList(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof Iterable<?> items) || value instanceof Map<?, ?>) {
      throw new IllegalArgumentException("'" + key + "' is not a list");
    }
    List<String> list = new ArrayList<>();
    for (Object item : items) {
      list.add(item == null ? "" : item.toString());
    }
    return list;
  }

  private static Map<String, String> stringMap(Map<String, Object> map, String key) {
    Map<String, Object> nested = object(map, key);
    if (nested == null) {
      return Map.of();
    }
    Map<String, String> strings = new LinkedHashMap<>();
    nested.forEach((k, v) -> strings.put(k, v == null ? "" : v.toString()));
    return strings;
  }
}

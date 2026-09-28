package eu.wohlben.qits.cirunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The suite's reading of an OTLP {@code ExportLogsServiceRequest}: a generic protobuf walk by field
 * number and wire type, written from opentelemetry-proto's field numbers rather than from {@link
 * OtlpLogs}' constants, so the encoder is checked against the schema and not against itself.
 */
final class OtlpDecode {

  /** One decoded log record, the fields the runner writes. */
  record Line(
      long timeUnixNano,
      long observedTimeUnixNano,
      int severityNumber,
      String severityText,
      String body,
      Map<String, String> attributes) {}

  /** A decoded request: its one resource and its one scope's records. */
  record Request(Map<String, String> resource, String scopeName, String scopeVersion, List<Line> lines) {}

  static Request decode(byte[] bytes) {
    Map<Integer, List<Object>> request = fields(bytes);
    List<Object> resourceLogs = request.getOrDefault(1, List.of());
    if (resourceLogs.size() != 1) {
      throw new AssertionError("expected one ResourceLogs, got " + resourceLogs.size());
    }
    Map<Integer, List<Object>> rl = fields((byte[]) resourceLogs.getFirst());
    Map<String, String> resource = keyValues(fields((byte[]) rl.get(1).getFirst()).get(1));
    Map<Integer, List<Object>> scopeLogs = fields((byte[]) rl.get(2).getFirst());
    Map<Integer, List<Object>> scope = fields((byte[]) scopeLogs.get(1).getFirst());
    List<Line> lines = new ArrayList<>();
    for (Object record : scopeLogs.getOrDefault(2, List.of())) {
      Map<Integer, List<Object>> r = fields((byte[]) record);
      lines.add(
          new Line(
              (Long) r.get(1).getFirst(),
              (Long) r.get(11).getFirst(),
              ((Long) r.get(2).getFirst()).intValue(),
              string(r.get(3).getFirst()),
              string(fields((byte[]) r.get(5).getFirst()).get(1).getFirst()),
              keyValues(r.get(6))));
    }
    return new Request(
        resource, string(scope.get(1).getFirst()), string(scope.get(2).getFirst()), lines);
  }

  private static Map<String, String> keyValues(List<Object> entries) {
    Map<String, String> map = new LinkedHashMap<>();
    if (entries == null) {
      return map;
    }
    for (Object entry : entries) {
      Map<Integer, List<Object>> kv = fields((byte[]) entry);
      map.put(
          string(kv.get(1).getFirst()),
          string(fields((byte[]) kv.get(2).getFirst()).get(1).getFirst()));
    }
    return map;
  }

  private static String string(Object bytes) {
    return new String((byte[]) bytes, StandardCharsets.UTF_8);
  }

  /** Every field of one message: varints and fixed64s as Long, length-delimited as byte[]. */
  static Map<Integer, List<Object>> fields(byte[] bytes) {
    Map<Integer, List<Object>> fields = new LinkedHashMap<>();
    int[] at = {0};
    while (at[0] < bytes.length) {
      long tag = varint(bytes, at);
      int field = (int) (tag >>> 3);
      Object value =
          switch ((int) (tag & 7)) {
            case 0 -> varint(bytes, at);
            case 1 -> {
              long v = 0;
              for (int i = 0; i < 8; i++) {
                v |= (bytes[at[0] + i] & 0xFFL) << (8 * i);
              }
              at[0] += 8;
              yield v;
            }
            case 2 -> {
              int length = (int) varint(bytes, at);
              byte[] v = new byte[length];
              System.arraycopy(bytes, at[0], v, 0, length);
              at[0] += length;
              yield v;
            }
            default -> throw new AssertionError("unexpected wire type in tag " + tag);
          };
      fields.computeIfAbsent(field, k -> new ArrayList<>()).add(value);
    }
    return fields;
  }

  private static long varint(byte[] bytes, int[] at) {
    long value = 0;
    int shift = 0;
    while (true) {
      byte b = bytes[at[0]++];
      value |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return value;
      }
      shift += 7;
    }
  }
}

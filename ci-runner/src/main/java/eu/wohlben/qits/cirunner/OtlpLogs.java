package eu.wohlben.qits.cirunner;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * An OTLP {@code ExportLogsServiceRequest}, written as protobuf by hand: the few fields a log line
 * needs of {@code opentelemetry/proto/collector/logs/v1} and {@code logs/v1}, and nothing else.
 *
 * <p><b>Why not the OpenTelemetry SDK, or {@code quarkus-opentelemetry}.</b> qits-observability
 * takes {@code http/protobuf} and nothing else (its README: OTLP/JSON and gRPC are not implemented),
 * so the choice was a protobuf runtime plus the SDK's exporter stack — generated classes, reflection
 * configuration and megabytes in a static binary whose every dependency is a decision — or the
 * sixty lines below. The wire format is stable by construction (protobuf field numbers never move),
 * the messages are four levels deep, and the suite decodes what this writes by the schema's field
 * numbers ({@code OtlpDecode}), not by these constants — and qits-observability's own receiver took
 * this encoding through the edge and answered it back record for record (2026-09-28). The
 * extension's static exporter headers would also have had no way to carry a bearer that expires
 * every few minutes.
 */
final class OtlpLogs {

  private OtlpLogs() {}

  /** One log line, as the handler captured it. */
  record Entry(long epochNanos, int severityNumber, String severityText, String body, String logger) {}

  // Field numbers, from opentelemetry-proto. Wire types: 0 varint, 1 fixed64, 2 length-delimited.
  private static final int REQUEST_RESOURCE_LOGS = 1;
  private static final int RESOURCE_LOGS_RESOURCE = 1;
  private static final int RESOURCE_LOGS_SCOPE_LOGS = 2;
  private static final int RESOURCE_ATTRIBUTES = 1;
  private static final int SCOPE_LOGS_SCOPE = 1;
  private static final int SCOPE_LOGS_LOG_RECORDS = 2;
  private static final int SCOPE_NAME = 1;
  private static final int SCOPE_VERSION = 2;
  private static final int RECORD_TIME_UNIX_NANO = 1;
  private static final int RECORD_SEVERITY_NUMBER = 2;
  private static final int RECORD_SEVERITY_TEXT = 3;
  private static final int RECORD_BODY = 5;
  private static final int RECORD_ATTRIBUTES = 6;
  private static final int RECORD_OBSERVED_TIME_UNIX_NANO = 11;
  private static final int KEY_VALUE_KEY = 1;
  private static final int KEY_VALUE_VALUE = 2;
  private static final int ANY_VALUE_STRING = 1;

  /** The OTel semantic-convention attribute a record's logger name travels in. */
  static final String LOGGER_ATTRIBUTE = "code.namespace";

  /** One request: one resource, one scope, every entry. */
  static byte[] encode(
      Map<String, String> resource, String scopeName, String scopeVersion, List<Entry> entries) {
    Proto res = new Proto();
    resource.forEach((k, v) -> res.message(RESOURCE_ATTRIBUTES, keyValue(k, v)));

    Proto scope = new Proto().string(SCOPE_NAME, scopeName).string(SCOPE_VERSION, scopeVersion);
    Proto scopeLogs = new Proto().message(SCOPE_LOGS_SCOPE, scope);
    for (Entry e : entries) {
      Proto record =
          new Proto()
              .fixed64(RECORD_TIME_UNIX_NANO, e.epochNanos())
              .varint(RECORD_SEVERITY_NUMBER, e.severityNumber())
              .string(RECORD_SEVERITY_TEXT, e.severityText())
              .message(RECORD_BODY, new Proto().string(ANY_VALUE_STRING, e.body()));
      if (e.logger() != null && !e.logger().isEmpty()) {
        record.message(RECORD_ATTRIBUTES, keyValue(LOGGER_ATTRIBUTE, e.logger()));
      }
      record.fixed64(RECORD_OBSERVED_TIME_UNIX_NANO, e.epochNanos());
      scopeLogs.message(SCOPE_LOGS_LOG_RECORDS, record);
    }

    Proto resourceLogs =
        new Proto()
            .message(RESOURCE_LOGS_RESOURCE, res)
            .message(RESOURCE_LOGS_SCOPE_LOGS, scopeLogs);
    return new Proto().message(REQUEST_RESOURCE_LOGS, resourceLogs).bytes();
  }

  /**
   * The OTel severity number for a {@code java.util.logging} level's value — JBoss' levels included
   * (FATAL 1100, ERROR 1000, WARN 900, INFO 800, DEBUG 500, TRACE 400).
   */
  static int severityNumber(int level) {
    if (level >= 1100) {
      return 21; // FATAL
    }
    if (level >= 1000) {
      return 17; // ERROR
    }
    if (level >= 900) {
      return 13; // WARN
    }
    if (level >= 800) {
      return 9; // INFO
    }
    if (level >= 500) {
      return 5; // DEBUG
    }
    return 1; // TRACE
  }

  private static Proto keyValue(String key, String value) {
    return new Proto()
        .string(KEY_VALUE_KEY, key)
        .message(KEY_VALUE_VALUE, new Proto().string(ANY_VALUE_STRING, value));
  }

  /** A message under construction. Empty strings and zeros are written anyway; proto3 allows both. */
  private static final class Proto {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Proto varint(int field, long value) {
      tag(field, 0);
      raw(value);
      return this;
    }

    Proto fixed64(int field, long value) {
      tag(field, 1);
      for (int i = 0; i < 8; i++) {
        out.write((int) (value >>> (8 * i)) & 0xFF);
      }
      return this;
    }

    Proto string(int field, String value) {
      return bytes(field, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    Proto message(int field, Proto message) {
      return bytes(field, message.bytes());
    }

    byte[] bytes() {
      return out.toByteArray();
    }

    private Proto bytes(int field, byte[] value) {
      tag(field, 2);
      raw(value.length);
      out.writeBytes(value);
      return this;
    }

    private void tag(int field, int wireType) {
      raw(((long) field << 3) | wireType);
    }

    private void raw(long value) {
      while ((value & ~0x7FL) != 0) {
        out.write((int) ((value & 0x7F) | 0x80));
        value >>>= 7;
      }
      out.write((int) value);
    }
  }
}

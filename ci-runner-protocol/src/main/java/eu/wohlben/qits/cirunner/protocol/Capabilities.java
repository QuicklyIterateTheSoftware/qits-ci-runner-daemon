package eu.wohlben.qits.cirunner.protocol;

import java.util.Map;

/**
 * What a runner says about the host it runs on, in its {@link Hello}.
 *
 * <p>{@code docker} is the one capability qits-ci matches on today: a pipeline with a {@code
 * docker: true} step needs a runner that will bind the host's docker socket into a step, and a
 * runner advertising {@code false} is never handed one. {@code arch} and {@code os} are the Go/OCI
 * spellings ({@code amd64}, {@code linux}) because that is the vocabulary an image's platform is
 * already written in, and a later match against one should not need a translation table.
 *
 * <p><b>{@code labels} is a map, not a list</b>, decided here: a label that carries a value
 * ({@code disk=ssd}, {@code site=home}) is the shape a matcher eventually needs, and a bare tag is
 * expressible as a key with an empty value while the reverse is not. They are stored and advertised,
 * not yet matched — the epic says so and this record does not pretend otherwise.
 */
public record Capabilities(boolean docker, String arch, String os, Map<String, String> labels) {

  public Capabilities {
    labels = labels == null ? Map.of() : Map.copyOf(labels);
  }
}

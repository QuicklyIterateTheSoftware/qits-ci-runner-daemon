package eu.wohlben.qits.cirunner;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the runner's own container was started with, as far as a successor inherits it — read from
 * {@code docker inspect} of itself, never from the install script's template. The container is the
 * record of what the person (or the previous rollover) actually ran, so a runner an operator started
 * with an extra variable or mount hands both on without anything here knowing about them.
 *
 * <p><b>The image's own contribution is subtracted.</b> {@code .Config.Env} and {@code
 * .Config.Labels} are the container's merged view: the image's {@code ENV}/{@code LABEL} lines plus
 * what {@code docker run} added. Carrying the merge would pin the <em>old</em> image's {@code PATH}
 * or {@code DOCKER_VERSION} onto the new image, so an entry exactly equal to the old image's is
 * dropped and the new image brings its own.
 *
 * @param env what the container was given, by key
 * @param labels likewise, minus the image's
 * @param binds {@code HostConfig.Binds}: each {@code -v} as it was written
 * @param mounts {@code HostConfig.Mounts}, rendered as {@code --mount} values
 * @param restart the restart policy as {@code --restart} spells it
 * @param network {@code HostConfig.NetworkMode}
 */
public record SelfSpec(
    String id,
    String image,
    Map<String, String> env,
    Map<String, String> labels,
    List<String> binds,
    List<String> mounts,
    String restart,
    String network) {

  /**
   * Parse {@link RunnerArgv#inspectSelf}'s answer, subtracting {@link RunnerArgv#imageConfig}'s.
   *
   * @throws IllegalArgumentException when either answer is not the JSON the templates produce
   */
  public static SelfSpec parse(String containerJson, String imageJson) {
    JsonObject container;
    JsonObject image;
    try {
      container = new JsonObject(containerJson.strip());
      image = new JsonObject(imageJson.strip());
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("docker inspect did not answer JSON: " + e.getMessage());
    }
    Set<String> imageEnv = new LinkedHashSet<>(strings(image.getJsonArray("env")));
    Map<String, String> env = new LinkedHashMap<>();
    for (String entry : strings(container.getJsonArray("env"))) {
      int split = entry.indexOf('=');
      if (split <= 0 || imageEnv.contains(entry)) {
        continue;
      }
      env.put(entry.substring(0, split), entry.substring(split + 1));
    }
    Map<String, String> imageLabels = labels(image.getJsonObject("labels"));
    Map<String, String> labels = new LinkedHashMap<>();
    for (Map.Entry<String, String> label : labels(container.getJsonObject("labels")).entrySet()) {
      if (!label.getValue().equals(imageLabels.get(label.getKey()))) {
        labels.put(label.getKey(), label.getValue());
      }
    }
    List<String> mounts = new ArrayList<>();
    JsonArray declared = container.getJsonArray("mounts");
    if (declared != null) {
      for (Object item : declared) {
        if (item instanceof JsonObject mount) {
          mounts.add(mount(mount));
        }
      }
    }
    return new SelfSpec(
        container.getString("id", ""),
        container.getString("image", ""),
        Map.copyOf(env),
        Map.copyOf(labels),
        List.copyOf(strings(container.getJsonArray("binds"))),
        List.copyOf(mounts),
        restart(container.getJsonObject("restart")),
        container.getString("network", ""));
  }

  /**
   * {@code --restart}'s spelling of a {@code RestartPolicy}. Docker reports "no policy" as an empty
   * name, which {@code --restart} does not accept, so it is spelled {@code no}.
   */
  private static String restart(JsonObject policy) {
    if (policy == null) {
      return "no";
    }
    String name = policy.getString("Name", "");
    if (name.isEmpty()) {
      return "no";
    }
    Integer retries = policy.getInteger("MaximumRetryCount", 0);
    return name.equals("on-failure") && retries != null && retries > 0 ? name + ":" + retries : name;
  }

  /** A {@code HostConfig.Mounts} entry as {@code --mount} takes it. */
  private static String mount(JsonObject mount) {
    StringBuilder out =
        new StringBuilder("type=")
            .append(mount.getString("Type", "volume"))
            .append(",source=")
            .append(mount.getString("Source", ""))
            .append(",target=")
            .append(mount.getString("Target", ""));
    if (mount.getBoolean("ReadOnly", false)) {
      out.append(",readonly");
    }
    return out.toString();
  }

  private static List<String> strings(JsonArray array) {
    List<String> out = new ArrayList<>();
    if (array != null) {
      for (Object item : array) {
        if (item instanceof String value) {
          out.add(value);
        }
      }
    }
    return out;
  }

  private static Map<String, String> labels(JsonObject object) {
    Map<String, String> out = new LinkedHashMap<>();
    if (object != null) {
      for (String key : object.fieldNames()) {
        Object value = object.getValue(key);
        out.put(key, value == null ? "" : String.valueOf(value));
      }
    }
    return out;
  }
}

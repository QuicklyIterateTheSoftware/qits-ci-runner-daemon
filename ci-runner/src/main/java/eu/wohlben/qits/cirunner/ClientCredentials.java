package eu.wohlben.qits.cirunner;

import io.vertx.core.json.JsonObject;
import java.net.URI;

/**
 * The idp client qits-ci commissioned for this runner, as the register door answered it and as
 * {@code client.json} keeps it. {@code registeredWith} is not the token — it is a SHA-256 of the
 * registration token that produced this client, which is how {@link Registration} tells "the same
 * container after a restart" from "an operator pasted a new token".
 */
public record ClientCredentials(
    String clientId,
    String secret,
    String tokenUrl,
    String audience,
    String socketUrl,
    String registeredWith) {

  /**
   * Read a client from its JSON. Refuses anything this binary could not dial with, so a truncated
   * or hand-edited file is one line in the log rather than a reconnect loop against nowhere.
   */
  public static ClientCredentials fromJson(JsonObject json, String registeredWith) {
    ClientCredentials client =
        new ClientCredentials(
            json.getString("clientId"),
            json.getString("secret"),
            json.getString("tokenUrl"),
            json.getString("audience"),
            json.getString("socketUrl"),
            registeredWith != null ? registeredWith : json.getString("registeredWith", ""));
    client.validate();
    return client;
  }

  public JsonObject toJson() {
    return new JsonObject()
        .put("clientId", clientId)
        .put("secret", secret)
        .put("tokenUrl", tokenUrl)
        .put("audience", audience)
        .put("socketUrl", socketUrl)
        .put("registeredWith", registeredWith);
  }

  private void validate() {
    require("clientId", clientId);
    require("secret", secret);
    require("tokenUrl", tokenUrl);
    require("socketUrl", socketUrl);
    scheme("tokenUrl", tokenUrl, "http", "https");
    scheme("socketUrl", socketUrl, "ws", "wss", "http", "https");
  }

  private static void require(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("the client carries no " + field);
    }
  }

  private static void scheme(String field, String value, String... allowed) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(field + " is not a url: '" + value + "'");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
    for (String candidate : allowed) {
      if (candidate.equals(scheme) && uri.getHost() != null) {
        return;
      }
    }
    throw new IllegalArgumentException(field + " is not a usable url: '" + value + "'");
  }

  /** Never the secret. */
  @Override
  public String toString() {
    return "ClientCredentials[clientId=" + clientId + ", socketUrl=" + socketUrl + "]";
  }
}

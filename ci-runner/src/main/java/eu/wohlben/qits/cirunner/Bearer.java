package eu.wohlben.qits.cirunner;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The runner's access token: a {@code client_credentials} grant at the client's {@code tokenUrl}
 * with its {@code audience}, held in memory only and refreshed before it expires.
 *
 * <p>Never on disk and never logged — the file on disk is the client, from which a token can always
 * be minted again, and a token written anywhere would be one more copy of a credential to protect.
 * It is minted when the socket dials, which is the only thing it is for; a socket that stays up
 * outlives the token harmlessly, because the edge checks it at the upgrade and not per frame.
 */
public final class Bearer {

  /** Refresh this long before {@code expires_in} runs out, capped at a fifth of the lifetime. */
  static final long MARGIN_SECONDS = 60;

  private final Http http;
  private final ClientCredentials client;
  private final LongSupplier nowMillis;

  private String token;
  private long refreshAtMillis;

  public Bearer(Http http, ClientCredentials client, LongSupplier nowMillis) {
    this.http = http;
    this.client = client;
    this.nowMillis = nowMillis;
  }

  /** A token that is not about to expire — the cached one, or a fresh mint. */
  public synchronized Future<String> token() {
    if (token != null && nowMillis.getAsLong() < refreshAtMillis) {
      return Future.succeededFuture(token);
    }
    String basic =
        Base64.getEncoder()
            .encodeToString(
                (form(client.clientId()) + ":" + form(client.secret()))
                    .getBytes(StandardCharsets.UTF_8));
    StringBuilder body = new StringBuilder("grant_type=client_credentials");
    if (client.audience() != null && !client.audience().isBlank()) {
      body.append("&audience=").append(form(client.audience()));
    }
    long requestedAt = nowMillis.getAsLong();
    return http.post(
            client.tokenUrl(),
            Map.of(
                "Authorization", "Basic " + basic,
                "Content-Type", "application/x-www-form-urlencoded",
                "Accept", "application/json"),
            body.toString())
        .compose(
            response -> {
              if (response.status() != 200) {
                // The body can echo the request; the status is what an operator can act on.
                return Future.failedFuture(
                    "the token endpoint answered " + response.status() + " for " + client.clientId());
              }
              JsonObject json = new JsonObject(response.body());
              String minted = json.getString("access_token");
              if (minted == null || minted.isBlank()) {
                return Future.failedFuture("the token endpoint answered without an access_token");
              }
              long lifetime = json.getLong("expires_in", 300L);
              long margin = Math.min(MARGIN_SECONDS, lifetime / 5);
              synchronized (this) {
                token = minted;
                refreshAtMillis = requestedAt + (lifetime - margin) * 1000L;
              }
              return Future.succeededFuture(minted);
            });
  }

  /** RFC 6749 §2.3.1: the pair is form-encoded before it is Basic-encoded. */
  private static String form(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}

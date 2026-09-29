package eu.wohlben.qits.cirunner;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The runner's access token: a {@code client_credentials} grant at the client's {@code tokenUrl}
 * with its {@code audience}, held in memory only and refreshed before it expires.
 *
 * <p><b>The client authenticates with {@code client_secret_post}</b> — its id and secret in the form
 * body — and never with HTTP Basic. A runner mints through the public edge, and the edge consumes a
 * Basic {@code Authorization} header as its own credential: the token endpoint behind it then sees
 * no client at all and answers 401 "client authentication is required" (measured live). The body is
 * the one place the edge leaves alone, so no {@code Authorization} header goes to the token endpoint.
 *
 * <p>Never on disk and never logged — the file on disk is the client, from which a token can always
 * be minted again, and a token written anywhere would be one more copy of a credential to protect.
 * It is minted when the socket dials, which is the only thing it is for; a socket that stays up
 * outlives the token harmlessly, because the edge and qits-ci check it at the upgrade and not per
 * frame. That second half needs qits-ci's side of it: Quarkus closes a WebSocket when its bearer's
 * {@code exp} passes, and until qits-ci opted its two sockets out, every runner dropped its socket
 * exactly one token lifetime (an hour) after it connected (qits-545).
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
    StringBuilder body =
        new StringBuilder("grant_type=client_credentials")
            .append("&client_id=")
            .append(form(client.clientId()))
            .append("&client_secret=")
            .append(form(client.secret()));
    if (client.audience() != null && !client.audience().isBlank()) {
      body.append("&audience=").append(form(client.audience()));
    }
    long requestedAt = nowMillis.getAsLong();
    return http.post(
            client.tokenUrl(),
            Map.of(
                "Content-Type", "application/x-www-form-urlencoded",
                "Accept", "application/json"),
            body.toString())
        .compose(
            response -> {
              if (response.status() != 200) {
                // The body can echo the request; the status is what an operator can act on.
                String why =
                    "the token endpoint answered " + response.status() + " for " + client.clientId();
                if (refusesTheClient(response.status(), response.body())) {
                  return Future.failedFuture(new ClientRefused(why + " (invalid_client)"));
                }
                return Future.failedFuture(why);
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

  /**
   * The token endpoint said, in OAuth's own words, that it does not know this client: {@code
   * invalid_client} as the {@code error} of a JSON body, on a 400 or a 401 (RFC 6749 5.2 allows
   * either). qits-idp answers exactly that for an unknown client id — which is what a runner's client
   * becomes when the runner is deleted — and for a wrong secret. Anything else is not this: a 5xx, a
   * 401 an edge wrote with some other body, a timeout, a refused connection — those are a platform
   * that is down or restarting, and are retried as ever.
   *
   * <p>This is evidence, not a verdict. One {@code invalid_client} could be an idp answering from a
   * store that is not ready yet; {@link ControlSocket} only believes a streak of them.
   */
  static boolean refusesTheClient(int status, String body) {
    if ((status != 400 && status != 401) || body == null || body.isBlank()) {
      return false;
    }
    try {
      return "invalid_client".equals(new JsonObject(body).getString("error"));
    } catch (RuntimeException notJson) {
      return false;
    }
  }

  /**
   * A mint the token endpoint refused with {@code invalid_client} — see {@link #refusesTheClient}.
   * Its own type so {@link ControlSocket} can tell it from every transient failure without reading a
   * message.
   */
  public static final class ClientRefused extends RuntimeException {
    public ClientRefused(String message) {
      super(message, null, false, false);
    }
  }

  /** {@code application/x-www-form-urlencoded}, RFC 6749 appendix B. */
  private static String form(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}

package eu.wohlben.qits.cirunner;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import java.util.Map;

/**
 * The plain HTTP calls this runner makes — the register door, the token endpoint and its log export
 * ({@link Telemetry}) — on the vertx-core client the WebSocket already brings. Not a REST client and
 * not Jackson: three POSTs do not justify either in a static binary.
 */
public final class Http {

  /** A response, whatever its status. Callers branch on {@link #status()}; nothing throws on 4xx. */
  public record Response(int status, String body) {}

  private final HttpClient client;
  private final long timeoutMillis;

  public Http(Vertx vertx, long timeoutMillis) {
    this.client = vertx.createHttpClient();
    this.timeoutMillis = timeoutMillis;
  }

  /** POST {@code body} to an absolute url. A transport failure fails the future. */
  public Future<Response> post(String url, Map<String, String> headers, String body) {
    return post(url, headers, Buffer.buffer(body));
  }

  /** POST a binary {@code body} to an absolute url. A transport failure fails the future. */
  public Future<Response> post(String url, Map<String, String> headers, Buffer body) {
    RequestOptions options =
        new RequestOptions()
            .setMethod(HttpMethod.POST)
            .setAbsoluteURI(url)
            .setConnectTimeout(timeoutMillis)
            .setIdleTimeout(timeoutMillis);
    headers.forEach(options::addHeader);
    return client
        .request(options)
        .compose(request -> request.send(body))
        .compose(
            response ->
                response.body().map(buffer -> new Response(response.statusCode(), buffer.toString())));
  }

  public void close() {
    client.close();
  }
}

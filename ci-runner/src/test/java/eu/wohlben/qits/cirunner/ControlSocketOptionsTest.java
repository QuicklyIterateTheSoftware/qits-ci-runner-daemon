package eu.wohlben.qits.cirunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.http.WebSocketConnectOptions;
import org.junit.jupiter.api.Test;

/**
 * The dial a {@code socketUrl} becomes. A runner outside the swarm is told {@code
 * wss://ci.qits.<domain>/ci/runners/socket} — the public edge, which forwards the upgrade — so {@code
 * wss} must dial TLS on 443 by host name (the JDK's trust store and SNI from the host name, Vert.x's
 * defaults); the {@code ws} of a qits-net alias stays plain.
 */
class ControlSocketOptionsTest {

  @Test
  void aWssUrlThroughTheEdgeDialsTlsOn443ByHostName() {
    WebSocketConnectOptions options =
        ControlSocket.optionsFor("wss://ci.qits.wohlben.eu/ci/runners/socket");

    assertTrue(options.isSsl());
    assertEquals("ci.qits.wohlben.eu", options.getHost());
    assertEquals(443, options.getPort());
    assertEquals("/ci/runners/socket", options.getURI());
  }

  @Test
  void anHttpsBaseIsTlsTooAndAnExplicitPortIsKept() {
    WebSocketConnectOptions options =
        ControlSocket.optionsFor("https://ci.example.org:8443/ci/runners/socket?x=1");

    assertTrue(options.isSsl());
    assertEquals(8443, options.getPort());
    assertEquals("/ci/runners/socket?x=1", options.getURI());
  }

  @Test
  void aWsAliasOnQitsNetStaysPlain() {
    WebSocketConnectOptions options =
        ControlSocket.optionsFor("ws://dev-qits-ci:8080/ci/runners/socket");

    assertFalse(options.isSsl());
    assertEquals("dev-qits-ci", options.getHost());
    assertEquals(8080, options.getPort());
  }
}

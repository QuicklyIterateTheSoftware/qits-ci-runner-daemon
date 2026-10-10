package eu.wohlben.qits.cirunner.contracts;

import java.util.List;

/**
 * <b>The REST calls the runner makes that no pact can bind yet</b> (qits-1149). Each provider lacks
 * the provider state and golden master the interaction must be built from. Each row is the
 * interaction as it will be written: provider, operation, request, the body paths the runner reads
 * (empty: status only), the trigger, and the provider state it needs. {@link PendingContractsTest}
 * reports every row as a skipped test that names that state, so the gap shows in every run.
 *
 * <p>When a provider publishes the state, replace the row with a real consumer pact test and add a
 * {@code contracts:} section to {@code .config/qits/release.yml} that publishes the pact jar.
 *
 * <p>An {@code operationId} in angle brackets does not exist yet: the provider's route has none, so
 * the name is this consumer's proposal.
 *
 * <p>Not REST, so not here: the control socket ({@code wss://.../ci/runners/socket}), the OTLP log
 * export ({@code POST .../observability/api/otel/v1/logs}, protobuf), and the docker and buildkit
 * traffic to the registry.
 */
final class PendingContracts {

  record Pending(
      String provider,
      String operationId,
      String method,
      String path,
      int status,
      String request,
      List<String> consumes,
      String trigger,
      String state,
      String recording) {

    String reason() {
      return "needs provider state '" + state + "' for " + operationId + " in " + provider;
    }
  }

  private static final String CI = "qits-ci-service";
  private static final String IDP = "qits-idp-service";

  static final List<Pending> ROWS =
      List.of(
          // --- qits-ci: the register door (Registration.register) ---------------------------------
          new Pending(
              CI,
              "<registerRunner>",
              "POST",
              "/ci/api/runners/{id}/register",
              200,
              "JSON {capabilities: {docker, arch, os, labels}}; Authorization: Bearer <registration"
                  + " token>",
              List.of("$.clientId", "$.secret", "$.tokenUrl", "$.audience", "$.socketUrl"),
              "Registration.ensure (first start, or a new registration token)",
              "an unregistered runner",
              "params runnerId and authorization (the Bearer of the runner's registration token);"
                  + " 200 with clientId, secret, tokenUrl (http(s)), audience, socketUrl (ws(s))"),
          new Pending(
              CI,
              "<registerRunner>",
              "POST",
              "/ci/api/runners/{id}/register",
              409,
              "as above",
              List.of(),
              "Registration.ensure (a 4xx exits REGISTRATION_REFUSED)",
              "a registered runner",
              "params runnerId and authorization; 409, the runner reads the status only"),
          // --- qits-idp: the token endpoint (Bearer.token) ----------------------------------------
          new Pending(
              IDP,
              "issueToken",
              "POST",
              "/idp/token",
              200,
              "form grant_type=client_credentials&client_id=..&client_secret=..&audience=..; the"
                  + " credentials in the body, no Basic header",
              List.of("$.access_token", "$.expires_in"),
              "Bearer.token (every socket dial, and the log export)",
              "a commissioned client",
              "params clientId and secret of the commissioned client (today the state gives"
                  + " clientId only); 200 with access_token and numeric expires_in"),
          new Pending(
              IDP,
              "issueToken",
              "POST",
              "/idp/token",
              401,
              "form as above, for a client the idp does not know",
              List.of("$.error"),
              "Bearer.token (ControlSocket stops after a streak of invalid_client)",
              "no commissioned client with the given id",
              "params clientId and secret of an unknown client; 401 (or 400) with error"
                  + " \"invalid_client\""));

  private PendingContracts() {}
}

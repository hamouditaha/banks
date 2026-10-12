package com.banks.fraud.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Checks the gateway access rules. No backend is registered (Eureka is off), so a request that
 * passes security ends in 503 from the load balancer: anything but 401/403 means "let through".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
@AutoConfigureWebTestClient
class GatewaySecurityTest {

    @Autowired
    private WebTestClient client;
    @LocalServerPort
    private int port;

    @Test
    void anonymousCallsAreRejected() {
        client.get().uri("/api/accounts").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/transfers").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void clientRoleCanReachBusinessRoutes() {
        assertLetThrough(client.mutateWith(withRealmRoles("CLIENT")).get().uri("/api/accounts").exchange());
        assertLetThrough(client.mutateWith(withRealmRoles("CLIENT")).get().uri("/api/transfers").exchange());
        assertLetThrough(client.mutateWith(withRealmRoles("CLIENT")).get().uri("/api/notifications/abc").exchange());
    }

    @Test
    void fraudRoutesAreReservedToAdmins() {
        client.mutateWith(withRealmRoles("CLIENT")).get().uri("/api/fraud/rules")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(withRealmRoles("CLIENT")).post().uri("/api/fraud/blacklist/abc")
                .exchange().expectStatus().isForbidden();
        assertLetThrough(client.mutateWith(withRealmRoles("ADMIN")).get().uri("/api/fraud/rules").exchange());
    }

    @Test
    void tokenWithoutRealmRolesCannotReachFraudRoutes() {
        client.mutateWith(withRealmRoles()).get().uri("/api/fraud/evaluations")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void corsPreflightIsAnsweredWithoutAToken() {
        httpClient().options().uri("/api/accounts")
                .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200");
    }

    @Test
    void unauthorizedResponsesStillCarryCorsHeaders() {
        httpClient().get().uri("/api/accounts")
                .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200");
    }

    @Test
    void healthIsPublic() {
        assertLetThrough(client.get().uri("/actuator/health").exchange());
    }

    /** Real HTTP client: CORS checks need an absolute request URI, which the mock-bound client lacks. */
    private WebTestClient httpClient() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    static JwtMutator withRealmRoles(String... roles) {
        return mockJwt()
                .jwt(jwt -> jwt.claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    static void assertLetThrough(WebTestClient.ResponseSpec response) {
        HttpStatus status = HttpStatus.valueOf(response.returnResult(Void.class).getStatus().value());
        assertThat(status).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}

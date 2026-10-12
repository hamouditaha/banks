package com.banks.fraud.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.banks.fraud.gateway.security.GatewaySecurityTest.assertLetThrough;

/** The "dev" profile keeps the gateway open for quick local demos without Keycloak. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
@AutoConfigureWebTestClient
@ActiveProfiles("dev")
class GatewayDevProfileTest {

    @Autowired
    private WebTestClient client;

    @Test
    void everythingIsOpenWithoutAToken() {
        assertLetThrough(client.get().uri("/api/accounts").exchange());
        assertLetThrough(client.get().uri("/api/fraud/rules").exchange());
        assertLetThrough(client.post().uri("/api/fraud/blacklist/abc").exchange());
    }
}

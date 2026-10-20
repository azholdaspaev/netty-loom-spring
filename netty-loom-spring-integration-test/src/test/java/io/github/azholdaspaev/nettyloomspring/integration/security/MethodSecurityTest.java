package io.github.azholdaspaev.nettyloomspring.integration.security;

import io.github.azholdaspaev.nettyloomspring.integration.support.BaseIntegrationTest;
import io.github.azholdaspaev.nettyloomspring.integration.support.JwkSetServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

class MethodSecurityTest extends BaseIntegrationTest {

    private final JwkSetServer jwkSetServer = JwkSetServer.getInstance();

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldAllowCallerWithAdminScope() {
        restTestClient.get().uri("/api/admin")
            .headers(headers -> headers.setBearerAuth(tokenWithScope("admin")))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("admin");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldForbidAuthenticatedCallerWithoutAdminScope() {
        restTestClient.get().uri("/api/admin")
            .headers(headers -> headers.setBearerAuth(tokenWithScope("read")))
            .exchange()
            .expectStatus().isForbidden();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldChallengeAnonymousCallerWith401() {
        restTestClient.get().uri("/api/admin")
            .exchange()
            .expectStatus().isUnauthorized();
    }

    private String tokenWithScope(String scope) {
        Instant now = Instant.now();
        return jwkSetServer.newToken(JwtClaimsSet.builder()
            .subject("alice")
            .claim("scope", scope)
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofMinutes(5)))
            .build());
    }
}

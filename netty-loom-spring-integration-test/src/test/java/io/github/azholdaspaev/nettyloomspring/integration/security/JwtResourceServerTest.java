package io.github.azholdaspaev.nettyloomspring.integration.security;

import io.github.azholdaspaev.nettyloomspring.integration.support.BaseIntegrationTest;
import io.github.azholdaspaev.nettyloomspring.integration.support.JwkSetServer;
import io.github.azholdaspaev.nettyloomspring.integration.web.ApiController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtResourceServerTest extends BaseIntegrationTest {

    private static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    private final JwkSetServer jwkSetServer = JwkSetServer.getInstance();

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldPassJwtClaimsToAuthenticationPrincipal() {
        String token = jwkSetServer.newToken(claimsExpiringAt(Instant.now().plus(Duration.ofMinutes(5))));

        restTestClient.get().uri("/api/me")
            .headers(headers -> headers.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
            .expectBody()
            .jsonPath("$.sub").isEqualTo("alice")
            .jsonPath("$." + ApiController.TEAM_CLAIM).isEqualTo("platform");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldDescribeCallerWhenJwtHasNoTeamClaim() {
        Instant now = Instant.now();
        String token = jwkSetServer.newToken(JwtClaimsSet.builder()
            .subject("alice")
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofMinutes(5)))
            .build());

        restTestClient.get().uri("/api/me")
            .headers(headers -> headers.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.sub").isEqualTo("alice")
            .jsonPath("$." + ApiController.TEAM_CLAIM).doesNotExist();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldRejectExpiredJwtWithInvalidTokenChallenge() {
        /*
         * Five minutes, not one second: JwtTimestampValidator accepts an expiry up to 60 seconds in
         * the past, so a barely expired token would be served and the test would fail for the wrong
         * reason.
         */
        String token = jwkSetServer.newToken(claimsExpiringAt(Instant.now().minus(Duration.ofMinutes(5))));

        expectInvalidTokenChallenge(token);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldRejectWronglySignedJwtWithInvalidTokenChallenge() {
        String token = jwkSetServer.newTokenSignedByWrongKey(claimsExpiringAt(Instant.now().plus(Duration.ofMinutes(5))));

        expectInvalidTokenChallenge(token);
    }

    private void expectInvalidTokenChallenge(String token) {
        restTestClient.get().uri("/api/me")
            .headers(headers -> headers.setBearerAuth(token))
            .exchange()
            .expectStatus().isUnauthorized()
            .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
            .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, challenge ->
                assertTrue(challenge.startsWith(INVALID_TOKEN_CHALLENGE),
                    "a rejected bearer token must be challenged as invalid_token; got " + challenge));
    }

    private static JwtClaimsSet claimsExpiringAt(Instant expiresAt) {
        return JwtClaimsSet.builder()
            .subject("alice")
            .claim(ApiController.TEAM_CLAIM, "platform")
            .issuedAt(expiresAt.minus(Duration.ofMinutes(10)))
            .expiresAt(expiresAt)
            .build();
    }
}

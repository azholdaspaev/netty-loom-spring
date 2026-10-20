package io.github.azholdaspaev.nettyloomspring.integration.security;

import io.github.azholdaspaev.nettyloomspring.integration.support.BaseIntegrationTest;
import io.github.azholdaspaev.nettyloomspring.integration.support.FormLogin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.concurrent.TimeUnit;

import static io.github.azholdaspaev.nettyloomspring.integration.support.FormLogin.SESSION_COOKIE;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FormLoginCsrfTest extends BaseIntegrationTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldRotateSessionIdOnLogin() {
        FormLogin.Login login = FormLogin.logIn(restTestClient);

        assertNotNull(login.preLoginSessionId(), "the login page must create the CSRF-token session");
        assertNotNull(login.postLoginSessionId(), "a successful login must emit a session cookie");
        assertNotEquals(login.preLoginSessionId(), login.postLoginSessionId(), "login must rotate the session id");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldAcceptPostCarryingSessionCookieAndCsrfToken() {
        String sessionId = FormLogin.logIn(restTestClient).postLoginSessionId();

        /*
         * Read after login, not reused from the login page: Spring Security replaces the session's CSRF
         * token on authentication, so the pre-login token would earn a 403 for the wrong reason.
         */
        restTestClient.post().uri("/form/echo")
            .header(HttpHeaders.COOKIE, SESSION_COOKIE + "=" + sessionId)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("message=hello&_csrf=" + FormLogin.encode(csrfTokenFor(sessionId)))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldRejectPostWithoutCsrfTokenWith403() {
        String sessionId = FormLogin.logIn(restTestClient).postLoginSessionId();

        restTestClient.post().uri("/form/echo")
            .header(HttpHeaders.COOKIE, SESSION_COOKIE + "=" + sessionId)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("message=hello")
            .exchange()
            .expectStatus().isForbidden();
    }

    private String csrfTokenFor(String sessionId) {
        String token = restTestClient.get().uri("/form/csrf")
            .header(HttpHeaders.COOKIE, SESSION_COOKIE + "=" + sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        assertNotNull(token, "an authenticated session must expose its CSRF token");
        return token;
    }
}

package io.github.azholdaspaev.nettyloomspring.autoconfigure.session;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.session.app.SessionTestApplication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.servlet.session.cookie.*} must reach the wire, not just the {@code SessionCookieConfig}
 * (issue #13). Each attribute here travels a different route: name, path and http-only through Boot's
 * {@code SessionConfiguringInitializer}; same-site through the factory, because Boot's initializer does
 * not map it; and all of them are finally encoded by {@code NettyHttpServletResponse.addCookie}.
 */
@AutoConfigureRestTestClient
@SpringBootTest(
    classes = SessionTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
    "server.servlet.session.cookie.name=SID",
    "server.servlet.session.cookie.path=/",
    "server.servlet.session.cookie.http-only=false",
    "server.servlet.session.cookie.max-age=60s",
    "server.servlet.session.cookie.same-site=lax"
})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SessionCookieConfigIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    private HttpHeaders createSessionResponseHeaders() {
        return restTestClient.get().uri("/session/set?value=hello")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult()
            .getResponseHeaders();
    }

    private String createSessionSetCookie() {
        return createSessionResponseHeaders().get(HttpHeaders.SET_COOKIE).getFirst();
    }

    private String createSessionId() {
        return SessionCookies.valueOf(createSessionResponseHeaders(), "SID");
    }

    @Test
    void theConfiguredNameAndAttributesAppearOnSetCookie() {
        String setCookie = createSessionSetCookie();

        assertTrue(setCookie.startsWith("SID="), "Actual: " + setCookie);
        assertTrue(setCookie.contains("Path=/"), "Actual: " + setCookie);
        assertTrue(setCookie.contains("Max-Age=60"), "Actual: " + setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"), "Actual: " + setCookie);
        assertFalse(setCookie.contains("HTTPOnly"), "http-only=false must be honoured: " + setCookie);
    }

    @Test
    void theConfiguredNameIsAlsoAcceptedOnTheFollowingRequest() {
        restTestClient.get().uri("/session/get")
            .cookie("SID", createSessionId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello");
    }

    @Test
    void theDefaultCookieNameNoLongerResolvesASession() {
        restTestClient.get().uri("/session/get")
            .cookie("JSESSIONID", createSessionId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("none");
    }
}

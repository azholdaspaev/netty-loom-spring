package io.github.azholdaspaev.nettyloomspring.autoconfigure.samesite;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.samesite.app.SameSiteTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.ResponseCookies;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettySessionCookieConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the deviation from Tomcat at {@code same-site=omitted} (issue #161) that
 * {@code NettyWebServerFactory.configureCookieSameSite} names.
 */
@AutoConfigureRestTestClient
@SpringBootTest(
    classes = SameSiteTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = "server.servlet.session.cookie.same-site=omitted")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CookieSameSiteSupplierOmittedIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    void shouldApplySupplierToSessionCookieWhenSameSiteIsOmitted() {
        String setCookie = ResponseCookies.lineFor(restTestClient.get().uri("/same-site/session")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult()
            .getResponseHeaders(), NettySessionCookieConfig.DEFAULT_NAME);

        assertNotNull(setCookie, "no Set-Cookie for " + NettySessionCookieConfig.DEFAULT_NAME);
        assertTrue(setCookie.contains("SameSite=Strict"),
            "same-site=omitted must leave the session cookie to the supplier. Actual: " + setCookie);
    }
}

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CookieSameSiteSupplier} beans must reach the wire (issue #85): Boot binds them onto the
 * factory for every servlet container, and this one used to drop them. Asserted on the real
 * {@code Set-Cookie} header rather than on the factory, because the whole defect was a value that
 * arrived and went nowhere.
 */
@AutoConfigureRestTestClient
@SpringBootTest(
    classes = SameSiteTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = "server.servlet.session.cookie.same-site=lax")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CookieSameSiteSupplierIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    private String setCookie(String uri, String name) {
        String line = ResponseCookies.lineFor(restTestClient.get().uri(uri)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult()
            .getResponseHeaders(), name);
        assertNotNull(line, "no Set-Cookie for " + name + " from " + uri);
        return line;
    }

    @Test
    void aSupplierMatchedCookieCarriesSameSiteOnTheWire() {
        String setCookie = setCookie("/same-site/tracked", "tracker");

        assertTrue(setCookie.contains("SameSite=Strict"), "Actual: " + setCookie);
    }

    @Test
    void anUnmatchedCookieCarriesNoSameSite() {
        String setCookie = setCookie("/same-site/plain", "plain");

        assertFalse(setCookie.contains("SameSite"), "Actual: " + setCookie);
    }

    @Test
    void theSessionSameSitePropertyWinsOverAMatchingSupplier() {
        String setCookie = setCookie("/same-site/session", NettySessionCookieConfig.DEFAULT_NAME);

        assertTrue(setCookie.contains("SameSite=Lax"), "Actual: " + setCookie);
        assertFalse(setCookie.contains("SameSite=Strict"), "Actual: " + setCookie);
    }
}

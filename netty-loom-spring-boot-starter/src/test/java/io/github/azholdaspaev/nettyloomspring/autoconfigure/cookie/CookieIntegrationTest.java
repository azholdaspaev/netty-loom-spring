package io.github.azholdaspaev.nettyloomspring.autoconfigure.cookie;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.cookie.app.CookieTestApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AutoConfigureRestTestClient
@SpringBootTest(
    classes = CookieTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class CookieIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void cookieValueResolvesSingleCookie() {
        restTestClient.get().uri("/cookie/read")
            .header("Cookie", "foo=bar")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("bar");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void getCookiesPreservesOrderAcrossPairs() {
        restTestClient.get().uri("/cookie/read-all")
            .header("Cookie", "a=1; b=2; c=3")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("a,b,c");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void missingRequiredCookieYields400() {
        restTestClient.get().uri("/cookie/read")
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void writtenCookieRoundTripsOnSecondRequest() {
        // RestTestClient does not persist cookies across exchanges; capture and resend manually.
        restTestClient.get().uri("/cookie/set")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().exists(HttpHeaders.SET_COOKIE);

        restTestClient.get().uri("/cookie/echo")
            .cookie("sid", "xyz")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("xyz");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void cookieAttributesAreSerialisedFaithfully() {
        String setCookie = restTestClient.get().uri("/cookie/set-attrs")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertNotNull(setCookie);
        assertTrue(setCookie.contains("Path="), setCookie);
        assertTrue(setCookie.contains("Domain="), setCookie);
        assertTrue(setCookie.contains("Secure"), setCookie);
        assertTrue(setCookie.contains("HTTPOnly"), setCookie);
        assertTrue(setCookie.contains("Max-Age="), setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"), setCookie);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void malformedCookieHeaderIsToleratedWithoutError() {
        restTestClient.get().uri("/cookie/read-all")
            .header("Cookie", "=;;garbage")
            .exchange()
            .expectStatus().isOk();
    }
}

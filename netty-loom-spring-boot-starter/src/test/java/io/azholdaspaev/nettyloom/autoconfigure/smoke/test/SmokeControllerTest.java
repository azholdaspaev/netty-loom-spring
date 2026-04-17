package io.azholdaspaev.nettyloom.autoconfigure.smoke.test;

import io.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeController.Greeting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

class SmokeControllerTest extends BaseIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldHandleGetRequest() {
        restTestClient.get().uri("/get")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldReturnJsonFromPojo() {
        restTestClient.get().uri("/api/greeting")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(Greeting.class).isEqualTo(new Greeting("hello"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldResolvePathVariable() {
        restTestClient.get().uri("/api/greeting/world")
            .exchange()
            .expectStatus().isOk()
            .expectBody(Greeting.class).isEqualTo(new Greeting("hello, world"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldResolveSingleRequestParam() {
        restTestClient.get().uri("/api/echo?msg=hi")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hi");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldExposeRepeatedQueryParameters() {
        restTestClient.get().uri("/api/params?a=1&a=2&b=x")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.a").isEqualTo(List.of("1", "2"))
            .jsonPath("$.b").isEqualTo(List.of("x"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldResolveRequestHeader() {
        restTestClient.get().uri("/api/whoami")
            .header("X-User", "alice")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("alice");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldNegotiateJsonWhenAcceptIsJson() {
        restTestClient.get().uri("/api/greeting")
            .header("Accept", MediaType.APPLICATION_JSON_VALUE)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldReturnNotAcceptableWhenAcceptDoesNotMatch() {
        restTestClient.get().uri("/api/greeting")
            .header("Accept", "text/csv")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.NOT_ACCEPTABLE);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldFormatDateHeaderAsRfc1123() {
        restTestClient.get().uri("/api/headers/date")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals(HttpHeaders.LAST_MODIFIED, "Thu, 01 Jan 1970 00:00:00 GMT");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldDeserializeJsonRequestBody() {
        restTestClient.post().uri("/api/greetings")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new Greeting("world"))
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(Greeting.class).isEqualTo(new Greeting("hello, world"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldBindFormUrlencodedBodyToRequestParam() {
        restTestClient.post().uri("/api/echo")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("msg=hello+form")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello form");
    }
}

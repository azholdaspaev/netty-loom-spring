package io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.test;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeController.Greeting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeControllerTest extends BaseIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @LocalServerPort
    private int port;

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

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldRoutePutWithRequestBodyAndPathVariable() {
        restTestClient.put().uri("/api/greetings/bob")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new Greeting("hi"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(Greeting.class).isEqualTo(new Greeting("bob says hi"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldReturnNoContentFromPut() {
        restTestClient.put().uri("/api/greetings/bob/ack")
            .exchange()
            .expectStatus().isNoContent()
            .expectBody().isEmpty();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldReadRawTextBodyViaReader() {
        restTestClient.put().uri("/api/notes/42")
            .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
            .body("café")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("CAFÉ");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldRoutePatchWithRequestBodyAndPathVariable() {
        restTestClient.patch().uri("/api/greetings/bob")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new Greeting("hi"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(Greeting.class).isEqualTo(new Greeting("bob updated to hi"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldBindFormUrlencodedBodyToRequestParamOnPatch() {
        restTestClient.patch().uri("/api/echo")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("msg=hello+patch")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello patch");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldReturnNoContentFromPatch() {
        restTestClient.patch().uri("/api/greetings/bob/touch")
            .exchange()
            .expectStatus().isNoContent()
            .expectBody().isEmpty();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldReturnNoContentFromDelete() {
        restTestClient.delete().uri("/api/greetings/bob")
            .exchange()
            .expectStatus().isNoContent()
            .expectBody().isEmpty();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldResolveQueryParamOnDelete() {
        restTestClient.delete().uri("/api/greetings?prefix=foo")
            .exchange()
            .expectStatus().isOk()
            .expectBody(Greeting.class).isEqualTo(new Greeting("deleted: foo"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldResolvePathVariableContainingAnEncodedSlash() throws Exception {
        // RestTestClient re-encodes the '%' of a URI template, sending "a%252Fb" -- which the unfixed
        // server decodes twice back to "a/b", so the same test through it passes against the bug.
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/greeting/a%2Fb")).build(),
                HttpResponse.BodyHandlers.ofString());
        }

        assertEquals(200, response.statusCode(),
            "an encoded slash is one segment, so it must not 404 against the single-segment mapping");
        assertEquals("{\"message\":\"hello, a/b\"}", response.body());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldReturnDeletedRepresentationFromDelete() {
        restTestClient.delete().uri("/api/greetings/bob/archive")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(Greeting.class).isEqualTo(new Greeting("archived: bob"));
    }
}

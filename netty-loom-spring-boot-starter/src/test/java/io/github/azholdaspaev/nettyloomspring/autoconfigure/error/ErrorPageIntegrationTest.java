package io.github.azholdaspaev.nettyloomspring.autoconfigure.error;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.error.app.ErrorTestApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@AutoConfigureRestTestClient
@SpringBootTest(
    classes = ErrorTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.web.error.include-message=always", "spring.autoconfigure.exclude="}
)
class ErrorPageIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    private RestTestClient.RequestHeadersSpec<?> getJson(String uri) {
        return restTestClient.get().uri(uri).accept(MediaType.APPLICATION_JSON);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aSpringGeneratedNotFoundGetsBootsErrorBody() {
        getJson("/definitely-not-mapped")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.path").isEqualTo("/definitely-not-mapped");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void anUncaughtExceptionGetsBootsErrorBodyWithTheOriginalPath() {
        getJson("/fail/exception")
            .exchange()
            .expectStatus().is5xxServerError()
            .expectBody()
            .jsonPath("$.status").isEqualTo(500)
            .jsonPath("$.path").isEqualTo("/fail/exception")
            .jsonPath("$.message").isEqualTo("the handler blew up");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void sendErrorGetsBootsErrorBodyWithThatStatusAndMessage() {
        getJson("/fail/send-error")
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.status").isEqualTo(403)
            .jsonPath("$.path").isEqualTo("/fail/send-error")
            .jsonPath("$.message").isEqualTo("no entry");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void anUnauthenticatedRequestGetsBootsErrorBody() {
        getJson("/secured/ping")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.status").isEqualTo(401)
            .jsonPath("$.path").isEqualTo("/secured/ping");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aForbiddenRequestGetsBootsErrorBody() {
        getJson("/secured/denied")
            .headers(headers -> headers.setBasicAuth("user", "pw"))
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.status").isEqualTo(403)
            .jsonPath("$.path").isEqualTo("/secured/denied");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aStatusSpecificErrorPageBeatsBootsGlobalOne() {
        restTestClient.get().uri("/fail/gone")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.GONE)
            .expectBody(String.class).isEqualTo("the gone page");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void anAcceptHtmlRequestGetsBootsWhitelabelPage() {
        restTestClient.get().uri("/fail/exception").accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().is5xxServerError()
            .expectBody(String.class).value(body ->
                assertTrue(body != null && body.contains("Whitelabel Error Page"), "got " + body));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void theErrorDispatchRunsErrorFiltersAndNotRequestOnlyOnes() {
        getJson("/fail/exception")
            .exchange()
            .expectHeader().valueEquals("X-Error-Filter", "ran")
            .expectHeader().doesNotExist("X-Request-Filter");
        // Both filters are mapped to /error and only the dispatcher type separates them, so a chain
        // built without one would show up as the wrong header, not as no header at all.
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aDirectRequestToTheErrorPageRunsRequestFiltersAndNotErrorOnlyOnes() {
        getJson("/error")
            .exchange()
            .expectHeader().valueEquals("X-Request-Filter", "ran")
            .expectHeader().doesNotExist("X-Error-Filter");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aSessionCreatedBeforeTheFailureStillReachesTheClient() {
        getJson("/fail/with-session")
            .exchange()
            .expectStatus().is5xxServerError()
            .expectHeader().value(HttpHeaders.SET_COOKIE, value ->
                assertTrue(value.contains("JSESSIONID"),
                    "reopening the response must not clear headers written before the failure; got " + value));
    }
}

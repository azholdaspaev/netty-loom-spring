package io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the two methods Spring MVC answers on its own — HEAD and OPTIONS — which
 * the bridge must not drop, mis-frame, or answer with headers it cannot honour.
 */
class HeadOptionsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldAnswerHeadWithGetHeadersAndContentLengthButNoBody() {
        EntityExchangeResult<byte[]> get = restTestClient.get().uri("/api/greeting")
            .exchange()
            .expectStatus().isOk()
            .expectBody().returnResult();

        long contentLength = get.getResponseHeaders().getContentLength();
        assertTrue(contentLength > 0, "GET must return a non-empty body for this test to mean anything");

        // Servlet-spec HEAD: identical status and headers to the GET, Content-Length still advertising
        // the bytes the GET would have sent, but no body on the wire.
        restTestClient.head().uri("/api/greeting")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectHeader().contentLength(contentLength)
            .expectBody().isEmpty();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldAnswerHeadOnUnknownPathWithEmptyNotFound() {
        restTestClient.head().uri("/does-not-exist")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody().isEmpty();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldAnswerOptionsWithAllowHeaderForMappedMethods() {
        // /api/echo maps GET, POST and PATCH; Spring derives HEAD from GET and always adds OPTIONS.
        EntityExchangeResult<byte[]> result = restTestClient.options().uri("/api/echo")
            .exchange()
            .expectStatus().isOk()
            .expectBody().returnResult();

        // Compared as a set: Spring builds Allow from a Set, so the ordering is not contractual.
        assertEquals(
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PATCH, HttpMethod.OPTIONS),
            result.getResponseHeaders().getAllow());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldAnswerOptionsOnUnknownPathWithNotFoundAndNoAllowHeader() {
        // Spring's HttpServlet.doOptions fallback would otherwise reflect over DispatcherServlet's do*
        // methods and stamp an Allow header onto this 404 — advertising methods no handler serves. A
        // real container swallows that write because sendError already committed the response.
        restTestClient.options().uri("/does-not-exist")
            .exchange()
            .expectStatus().isNotFound()
            .expectHeader().doesNotExist(HttpHeaders.ALLOW);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldAnswerCorsPreflightWithAccessControlHeaders() {
        restTestClient.options().uri("/api/cors/echo")
            .header(HttpHeaders.ORIGIN, "https://allowed.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Custom")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://allowed.example")
            .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, value -> assertTrue(value.contains("POST")))
            .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, value -> assertTrue(value.contains("X-Custom")));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldAnswerUnsupportedMethodWithAllowHeader() {
        // /get maps GET only. The Allow header is added by Spring's exception resolver *before* it calls
        // sendError, so it must survive the commit — unlike the post-commit write above.
        EntityExchangeResult<byte[]> result = restTestClient.post().uri("/get")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
            .expectBody().returnResult();

        assertTrue(result.getResponseHeaders().getAllow().contains(HttpMethod.GET));
    }
}

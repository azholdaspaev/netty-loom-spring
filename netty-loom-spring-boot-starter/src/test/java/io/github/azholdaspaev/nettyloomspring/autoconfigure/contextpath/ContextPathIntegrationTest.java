package io.github.azholdaspaev.nettyloomspring.autoconfigure.contextpath;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.contextpath.app.ContextPathTestApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #49: {@code server.servlet.context-path} must mount the application under the given path.
 * In-context requests route (200); out-of-context requests are rejected (404). {@code RestTestClient}
 * auto-prepends the context path to relative URIs, so out-of-context requests use an absolute URL via
 * {@link LocalServerPort}.
 */
@AutoConfigureRestTestClient
@SpringBootTest(
    classes = ContextPathTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = "server.servlet.context-path=/app")
class ContextPathIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @LocalServerPort
    private int port;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void relativeRequestIsMountedUnderContextPath() {
        // RestTestClient prepends "/app" to the relative URI, so this hits /app/hello.
        restTestClient.get().uri("/hello")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("/app");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void inContextAbsoluteRequestRoutes() throws Exception {
        HttpResponse<String> response = get("http://localhost:" + port + "/app/hello");

        assertEquals(200, response.statusCode());
        assertEquals("/app", response.body());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void outOfContextAbsoluteRequestYields404() throws Exception {
        HttpResponse<String> response = get("http://localhost:" + port + "/hello");

        assertEquals(404, response.statusCode());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void filterMappedByContextRelativePatternMatches() {
        restTestClient.get().uri("/hello")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Ctx-Filter", "yes");
    }

    private static HttpResponse<String> get(String uri) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                HttpRequest.newBuilder(URI.create(uri)).build(),
                HttpResponse.BodyHandlers.ofString());
        }
    }
}

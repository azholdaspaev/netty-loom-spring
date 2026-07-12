package io.github.azholdaspaev.nettyloomspring.autoconfigure.resources;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.resources.app.StaticResourceApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.concurrent.TimeUnit;

@AutoConfigureRestTestClient
@SpringBootTest(
    classes = StaticResourceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class StaticResourceIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    /**
     * Boot's default static locations (here {@code classpath:/static/}) are served by Spring's
     * ResourceHttpRequestHandler straight off the classpath — they never go through the
     * ServletContext. What does go through it is {@code getMimeType}, which the handler calls
     * unconditionally once it has found a resource.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldServeResourceFromClasspathStatic() {
        restTestClient.get().uri("/hello.txt")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
            .expectBody(String.class).isEqualTo("hello from static\n");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldReturnNotFoundForMissingResource() {
        restTestClient.get().uri("/missing.txt")
            .exchange()
            .expectStatus().isNotFound();
    }
}

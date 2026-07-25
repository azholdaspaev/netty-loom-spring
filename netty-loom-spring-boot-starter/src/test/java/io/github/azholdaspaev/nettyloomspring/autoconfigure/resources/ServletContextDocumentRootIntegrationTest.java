package io.github.azholdaspaev.nettyloomspring.autoconfigure.resources;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.resources.app.StaticResourceApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.concurrent.TimeUnit;

/**
 * Proves that the ServletContext document root serves resources on its own.
 *
 * <p>Boot's default static locations already include {@code classpath:/META-INF/resources/}, so under
 * the normal configuration a hit on {@code /meta.txt} says nothing about whether
 * {@code DefaultNettyServletContext.getResource} works — Spring's classpath resolution would have
 * served it regardless. Emptying {@code spring.web.resources.static-locations} removes every classpath
 * location, leaving the {@code ServletContextResource("/")} that {@code WebMvcAutoConfiguration}
 * registers as the sole remaining location. A resource served under that configuration can only have
 * come through the ServletContext.
 */
@AutoConfigureRestTestClient
@SpringBootTest(
    classes = StaticResourceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.web.resources.static-locations="
)
class ServletContextDocumentRootIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldServeDocumentRootResourceThroughServletContext() {
        restTestClient.get().uri("/meta.txt")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello from META-INF/resources\n");
    }

    /**
     * Guards the test above: {@code /hello.txt} lives only in {@code classpath:/static/}, which is not
     * part of the document root. Its 404 confirms the classpath locations really are disabled here, so
     * the sibling test's 200 cannot be explained by them.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldNotServeClasspathStaticWhenStaticLocationsDisabled() {
        restTestClient.get().uri("/hello.txt")
            .exchange()
            .expectStatus().isNotFound();
    }
}

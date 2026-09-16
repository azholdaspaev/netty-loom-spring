package io.github.azholdaspaev.nettyloomspring.autoconfigure.welcome;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.welcome.app.WelcomePageTestApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The welcome page is served from its own location rather than the default {@code classpath:/static/},
 * which every test application in this module shares and would otherwise answer at {@code /}.
 */
@AutoConfigureRestTestClient
@SpringBootTest(
    classes = WelcomePageTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.web.resources.static-locations=classpath:/welcome/"
)
class WelcomePageIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldServeStaticIndexHtmlAtRoot() {
        restTestClient.get().uri("/")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class).value(body ->
                assertTrue(body.contains("welcome page"), "GET / should forward to index.html; got " + body));
    }
}

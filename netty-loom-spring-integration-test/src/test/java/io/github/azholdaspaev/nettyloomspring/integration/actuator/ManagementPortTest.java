package io.github.azholdaspaev.nettyloomspring.integration.actuator;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.server.NettyServletWebServer;
import io.github.azholdaspaev.nettyloomspring.integration.app.RestServiceApplication;
import io.github.azholdaspaev.nettyloomspring.integration.support.JwkSetServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(
    classes = {RestServiceApplication.class, ManagementPortTest.ManagementServerCapture.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0"
)
class ManagementPortTest {

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ManagementServerCapture managementServerCapture;

    @DynamicPropertySource
    static void registerJwkSetUri(DynamicPropertyRegistry registry) {
        JwkSetServer.registerJwkSetUri(registry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/readiness"})
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldServeHealthOnManagementPort(String path) throws Exception {
        assertEquals(200, statusOf(managementPort, path), path + " must answer on the management port");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldServeManagementPortFromSecondNettyServer() {
        WebServer managementServer = managementServerCapture.managementServer;

        assertInstanceOf(NettyServletWebServer.class, managementServer,
            "the management child context must be served by its own Netty server");
        assertEquals(managementPort, managementServer.getPort(), "that server must listen on the management port");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldAnswer404ForHealthOnServerPort() throws Exception {
        assertEquals(404, statusOf(serverPort, "/actuator/health"),
            "with a management port set, the application port must no longer serve /actuator");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldAnswer404ForItemRouteOnManagementPort() throws Exception {
        assertEquals(404, statusOf(managementPort, "/items/1"),
            "the management server must dispatch to the management context, not the application's");
    }

    private static int statusOf(int port, String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ManagementServerCapture implements ApplicationListener<WebServerInitializedEvent> {

        // Written on the startup thread before the test runs, read on the test thread.
        private volatile WebServer managementServer;

        @Override
        public void onApplicationEvent(WebServerInitializedEvent event) {
            if ("management".equals(event.getApplicationContext().getServerNamespace())) {
                managementServer = event.getWebServer();
            }
        }
    }
}

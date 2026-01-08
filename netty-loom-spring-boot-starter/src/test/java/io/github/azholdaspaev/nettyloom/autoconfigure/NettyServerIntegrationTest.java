package io.github.azholdaspaev.nettyloom.autoconfigure;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that verify the complete Netty server stack works with Spring MVC.
 * These tests boot a real application with the Netty server and make HTTP requests.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = NettyServerIntegrationTest.TestApplication.class
)
class NettyServerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Nested
    class BasicRequests {

        @Test
        void shouldRespondToGetRequest() {
            ResponseEntity<String> response = restTemplate.getForEntity("/hello", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("Hello World");
        }

        @Test
        void shouldReturnCorrectPort() {
            assertThat(port).isGreaterThan(0);
        }
    }

    @Nested
    class JsonSerialization {

        @Test
        void shouldSerializeJsonResponse() {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                    "/json", (Class<Map<String, Object>>) (Class<?>) Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("status", "ok")
                    .containsEntry("message", "JSON works");
        }

        @Test
        void shouldDeserializeJsonRequest() {
            Map<String, String> request = Map.of("name", "Test User");

            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/echo", request, String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("Test User");
        }
    }

    @Nested
    class PathVariables {

        @Test
        void shouldHandlePathVariables() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "/greet/John", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("Hello, John!");
        }
    }

    @Nested
    class QueryParameters {

        @Test
        void shouldHandleQueryParameters() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "/search?q=test&limit=10", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("Search: test, limit: 10");
        }
    }

    @Nested
    class VirtualThreads {

        @Test
        void shouldProcessRequestOnVirtualThread() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "/thread-info", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("virtual=true");
        }
    }

    @SpringBootApplication
    @ComponentScan(excludeFilters = @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ".*AutoConfigurationTest.*"
    ))
    static class TestApplication {

        @RestController
        static class TestController {

            @GetMapping("/hello")
            public String hello() {
                return "Hello World";
            }

            @GetMapping("/json")
            public Map<String, Object> json() {
                return Map.of(
                        "status", "ok",
                        "message", "JSON works"
                );
            }

            @PostMapping("/echo")
            public String echo(@RequestBody Map<String, String> body) {
                return "Received: " + body.get("name");
            }

            @GetMapping("/greet/{name}")
            public String greet(@PathVariable String name) {
                return "Hello, " + name + "!";
            }

            @GetMapping("/search")
            public String search(
                    @RequestParam String q,
                    @RequestParam(defaultValue = "20") int limit) {
                return "Search: " + q + ", limit: " + limit;
            }

            @GetMapping("/thread-info")
            public String threadInfo() {
                Thread current = Thread.currentThread();
                return "thread=" + current.getName() + ", virtual=" + current.isVirtual();
            }
        }
    }
}

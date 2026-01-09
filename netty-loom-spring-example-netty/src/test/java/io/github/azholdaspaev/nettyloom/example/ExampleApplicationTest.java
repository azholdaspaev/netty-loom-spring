package io.github.azholdaspaev.nettyloom.example;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the example application.
 * Verifies all benchmark endpoints work correctly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExampleApplicationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Nested
    class HelloEndpoint {

        @Test
        void shouldReturnHelloWorld() {
            // Given - no setup needed

            // When
            ResponseEntity<String> response = restTemplate.getForEntity("/hello", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("Hello World");
        }
    }

    @Nested
    class JsonEndpoint {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnJsonObject() {
            // Given - no setup needed

            // When
            ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>)
                    (ResponseEntity<?>) restTemplate.getForEntity("/json", Map.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKeys("timestamp", "message", "data", "metadata");
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldContainTimestamp() {
            // Given - no setup needed

            // When
            ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>)
                    (ResponseEntity<?>) restTemplate.getForEntity("/json", Map.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("timestamp")).isNotNull();
            assertThat(response.getBody().get("timestamp").toString()).isNotEmpty();
        }
    }

    @Nested
    class DbEndpoint {

        @Test
        @SuppressWarnings("unchecked")
        void shouldSimulateDbCall() {
            // Given - no setup needed

            // When
            ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>)
                    (ResponseEntity<?>) restTemplate.getForEntity("/db", Map.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKeys("result", "latency", "thread", "virtual");
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldTakeAtLeast100ms() {
            // Given
            long startTime = System.currentTimeMillis();

            // When
            ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>)
                    (ResponseEntity<?>) restTemplate.getForEntity("/db", Map.class);
            long duration = System.currentTimeMillis() - startTime;

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(duration).isGreaterThanOrEqualTo(100);
        }
    }

    @Nested
    class MixedEndpoint {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnComputedAndFetchedData() {
            // Given - no setup needed

            // When
            ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>)
                    (ResponseEntity<?>) restTemplate.getForEntity("/mixed", Map.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKeys("computed", "fetched", "cpuDuration", "ioLatency");
            assertThat(response.getBody().get("computed")).isNotNull();
            assertThat(response.getBody().get("fetched")).isNotNull();
        }
    }
}

package io.github.azholdaspaev.nettyloom.autoconfigure.smoke.test;

import static io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeTestController.CONTROLLER_PATH;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.azholdaspaev.nettyloom.autoconfigure.NettyLoomAutoConfiguration;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeTestApplication;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeTestController.TestDto;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        classes = {
            SmokeTestApplication.class,
            NettyLoomAutoConfiguration.class,
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SmokeTestControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldReturnHelloResponse() {
        // Given
        var typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

        // When
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(CONTROLLER_PATH, HttpMethod.GET, null, typeRef);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("message", "hello");
        assertThat(body).containsKey("virtual");
        assertThat(body).containsKey("thread");
    }

    @Test
    void shouldRunOnVirtualThread() {
        // Given
        var typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

        // When
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(CONTROLLER_PATH, HttpMethod.GET, null, typeRef);

        // Then
        assertThat(response.getBody()).containsEntry("virtual", true);
    }

    @Test
    void shouldEchoDto() {
        // Given
        var dto = new TestDto("test", 42);

        // When
        ResponseEntity<TestDto> response = restTemplate.postForEntity(CONTROLLER_PATH + "/echo", dto, TestDto.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("test");
        assertThat(response.getBody().value()).isEqualTo(42);
    }

    @Test
    void shouldReturnTimestamp() {
        // Given
        var typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

        // When
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(CONTROLLER_PATH, HttpMethod.GET, null, typeRef);

        // Then
        assertThat(response.getBody())
                .containsKey("timestamp")
                .extractingByKey("timestamp")
                .satisfies(ts -> assertThat(ts.toString()).isNotEmpty());
    }
}

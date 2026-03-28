package io.github.azholdaspaev.nettyloom.autoconfigure.smoke.test;

import io.github.azholdaspaev.nettyloom.autoconfigure.NettyLoomAutoConfiguration;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeTestApplication;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.GetResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeRestController.CONTROLLER_PATH;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = {
        SmokeTestApplication.class,
        NettyLoomAutoConfiguration.class,
    },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
public class SmokeRestControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldReturnValueForGetRequestWithSingleQueryParameter() {
        // Given
        var typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

        // When
        ResponseEntity<Map<String, Object>> response =
            restTemplate.exchange(CONTROLLER_PATH + "/query/single?value=some", HttpMethod.GET, null, typeRef);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("value", "some");
    }

    @Test
    void shouldReturnValueForGetRequestWithMultipleQueryParameter() {
        // Given
        var typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

        // When
        ResponseEntity<Map<String, Object>> response =
            restTemplate.exchange(CONTROLLER_PATH + "/query/multiple?first=some&second=10&third=1", HttpMethod.GET, null, typeRef);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("first", "some");
        assertThat(body).containsEntry("second", 10);
        assertThat(body).containsEntry("third", 1);
    }

    @Test
    void shouldReturnValueForGetRequestWithResponseDto() {
        // Given
        var id = 1L;
        var name = "name";
        var item = "item";

        // When
        ResponseEntity<GetResponse> response =
            restTemplate.exchange(CONTROLLER_PATH + "/query/response/dto?id=1&name=name&item=item", HttpMethod.GET, null, GetResponse.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new GetResponse(id, name, List.of(item)));
    }
}

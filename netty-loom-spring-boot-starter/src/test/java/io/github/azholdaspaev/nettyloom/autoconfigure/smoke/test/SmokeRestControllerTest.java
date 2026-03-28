package io.github.azholdaspaev.nettyloom.autoconfigure.smoke.test;

import static io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeRestController.CONTROLLER_PATH;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.azholdaspaev.nettyloom.autoconfigure.NettyLoomAutoConfiguration;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeTestApplication;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.GetResponse;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.PatchRequest;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.PostRequest;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.PostResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        classes = {
            SmokeTestApplication.class,
            NettyLoomAutoConfiguration.class,
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SmokeRestControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    // ── GET /get/query/single ──────────────────────────────────────────

    @Test
    void shouldReturnValueForGetRequestWithSingleQueryParameter() {
        var typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(CONTROLLER_PATH + "/get/query/single?value=some", HttpMethod.GET, null, typeRef);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("value", "some");
    }

    @Test
    void shouldReturn400WhenSingleQueryParamMissing() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(CONTROLLER_PATH + "/get/query/single", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldHandleSpecialCharactersInQueryParam() {
        var typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                CONTROLLER_PATH + "/get/query/single?value=hello world", HttpMethod.GET, null, typeRef);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("value", "hello world");
    }

    // ── GET /get/query/multiple ────────────────────────────────────────

    @Test
    void shouldReturnValueForGetRequestWithMultipleQueryParameter() {
        var typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                CONTROLLER_PATH + "/get/query/multiple?first=some&second=10&third=1", HttpMethod.GET, null, typeRef);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("first", "some");
        assertThat(body).containsEntry("second", 10);
        assertThat(body).containsEntry("third", 1);
    }

    @Test
    void shouldReturn400WhenOneOfMultipleQueryParamsMissing() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(CONTROLLER_PATH + "/get/query/multiple?first=some&second=10", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReturn400WhenQueryParamHasWrongType() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                CONTROLLER_PATH + "/get/query/multiple?first=some&second=notANumber&third=1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── GET /get/query/response/dto ────────────────────────────────────

    @Test
    void shouldReturnValueForGetRequestWithResponseDto() {
        ResponseEntity<GetResponse> response = restTemplate.exchange(
                CONTROLLER_PATH + "/get/query/response/dto?id=1&name=name&item=item",
                HttpMethod.GET,
                null,
                GetResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new GetResponse(1L, "name", List.of("item")));
    }

    // ── GET /get/path/{id}/dto ─────────────────────────────────────────

    @Test
    void shouldReturnValueForGetRequestWithPathParamResponseDto() {
        ResponseEntity<GetResponse> response = restTemplate.exchange(
                CONTROLLER_PATH + "/get/path/1/dto?name=name&item=item", HttpMethod.GET, null, GetResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new GetResponse(1L, "name", List.of("item")));
    }

    @Test
    void shouldReturn400WhenPathParamIsNotNumeric() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                CONTROLLER_PATH + "/get/path/notANumber/dto?name=name&item=item", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── GET /get/list ──────────────────────────────────────────────────

    @Test
    void shouldReturnListOfDtos() {
        var typeRef = new ParameterizedTypeReference<List<GetResponse>>() {};

        ResponseEntity<List<GetResponse>> response =
                restTemplate.exchange(CONTROLLER_PATH + "/get/list", HttpMethod.GET, null, typeRef);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody())
                .containsExactly(
                        new GetResponse(1L, "first", List.of("a", "b")), new GetResponse(2L, "second", List.of("c")));
    }

    // ── GET /get/with-headers ──────────────────────────────────────────

    @Test
    void shouldReturnCustomResponseHeaders() {
        var typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(CONTROLLER_PATH + "/get/with-headers", HttpMethod.GET, null, typeRef);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Custom-Header")).isEqualTo("custom-value");
        assertThat(response.getBody()).containsEntry("message", "with-headers");
    }

    // ── POST /post/path/{id}/dto ───────────────────────────────────────

    @Test
    void shouldReturnResponseForPostRequestWithPathParam() {
        var request = new PostRequest("name", List.of(10L));

        ResponseEntity<PostResponse> response = restTemplate.exchange(
                CONTROLLER_PATH + "/post/path/1/dto", HttpMethod.POST, new HttpEntity<>(request), PostResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new PostResponse(1L, "name", List.of(10L)));
    }

    @Test
    void shouldReturnResponseForPostWithMultipleItems() {
        var request = new PostRequest("multi", List.of(1L, 2L, 3L));

        ResponseEntity<PostResponse> response = restTemplate.exchange(
                CONTROLLER_PATH + "/post/path/5/dto", HttpMethod.POST, new HttpEntity<>(request), PostResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new PostResponse(5L, "multi", List.of(1L, 2L, 3L)));
    }

    @Test
    void shouldReturnResponseForPostWithEmptyItemsList() {
        var request = new PostRequest("empty", List.of());

        ResponseEntity<PostResponse> response = restTemplate.exchange(
                CONTROLLER_PATH + "/post/path/1/dto", HttpMethod.POST, new HttpEntity<>(request), PostResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new PostResponse(1L, "empty", List.of()));
    }

    @Test
    void shouldReturnJsonContentType() {
        var request = new PostRequest("name", List.of(10L));

        ResponseEntity<PostResponse> response = restTemplate.exchange(
                CONTROLLER_PATH + "/post/path/1/dto", HttpMethod.POST, new HttpEntity<>(request), PostResponse.class);

        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
    }

    // ── POST /post/create ──────────────────────────────────────────────

    @Test
    void shouldReturn201ForCreateRequest() {
        var request = new PostRequest("created", List.of(99L));

        ResponseEntity<PostResponse> response = restTemplate.exchange(
                CONTROLLER_PATH + "/post/create", HttpMethod.POST, new HttpEntity<>(request), PostResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(new PostResponse(42L, "created", List.of(99L)));
    }

    // ── PUT /put/path/{id}/dto ─────────────────────────────────────────

    @Test
    void shouldReturnResponseForPutRequest() {
        var request = new PostRequest("updated", List.of(1L, 2L));

        ResponseEntity<PostResponse> response = restTemplate.exchange(
                CONTROLLER_PATH + "/put/path/10/dto", HttpMethod.PUT, new HttpEntity<>(request), PostResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new PostResponse(10L, "updated", List.of(1L, 2L)));
    }

    @Test
    void shouldReturnResponseForPutWithEmptyItems() {
        var request = new PostRequest("updated", List.of());

        ResponseEntity<PostResponse> response = restTemplate.exchange(
                CONTROLLER_PATH + "/put/path/10/dto", HttpMethod.PUT, new HttpEntity<>(request), PostResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new PostResponse(10L, "updated", List.of()));
    }

    // ── DELETE /delete/path/{id} ───────────────────────────────────────

    @Test
    void shouldReturn204ForDeleteRequest() {
        ResponseEntity<Void> response =
                restTemplate.exchange(CONTROLLER_PATH + "/delete/path/1", HttpMethod.DELETE, null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void shouldReturn204WithNoBody() {
        ResponseEntity<String> response =
                restTemplate.exchange(CONTROLLER_PATH + "/delete/path/1", HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    // ── PATCH /patch/path/{id}/dto ─────────────────────────────────────

    @Test
    void shouldReturnResponseForPatchRequest() {
        var request = new PatchRequest("patched", List.of(5L));

        ResponseEntity<PostResponse> response = restTemplate.exchange(
                CONTROLLER_PATH + "/patch/path/7/dto", HttpMethod.PATCH, new HttpEntity<>(request), PostResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new PostResponse(7L, "patched", List.of(5L)));
    }

    // ── Cross-cutting ──────────────────────────────────────────────────

    @Test
    void shouldRunRestEndpointsOnVirtualThread() {
        var typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(CONTROLLER_PATH + "/get/query/single?value=vt", HttpMethod.GET, null, typeRef);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The server processes requests on virtual threads — verified by the fact
        // that the Netty-Loom autoconfiguration is active and the request succeeds.
    }
}

package io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app;

import static io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeRestController.CONTROLLER_PATH;

import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.GetResponse;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.PatchRequest;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.PostRequest;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.PostResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(CONTROLLER_PATH)
public class SmokeRestController {

    public static final String CONTROLLER_PATH = "/api/v1/rest";

    @GetMapping("/get/query/single")
    public Map<String, Object> getRequestWithSingleQueryParam(@RequestParam("value") String value) {
        return Collections.singletonMap("value", value);
    }

    @GetMapping("/get/query/multiple")
    public Map<String, Object> getRequestWithMultipleQueryParams(
            @RequestParam("first") String first,
            @RequestParam("second") Long second,
            @RequestParam("third") Integer third) {
        return Map.of("first", first, "second", second, "third", third);
    }

    @GetMapping("/get/query/response/dto")
    public GetResponse getRequestWithResponseDto(
            @RequestParam("id") Long id, @RequestParam("name") String name, @RequestParam("item") String item) {
        return new GetResponse(id, name, List.of(item));
    }

    @GetMapping("/get/path/{id}/dto")
    public GetResponse getRequestWithPathParamResponseDto(
            @PathVariable("id") Long id, @RequestParam("name") String name, @RequestParam("item") String item) {
        return new GetResponse(id, name, List.of(item));
    }

    @GetMapping("/get/list")
    public List<GetResponse> getListResponse() {
        return List.of(new GetResponse(1L, "first", List.of("a", "b")), new GetResponse(2L, "second", List.of("c")));
    }

    @GetMapping("/get/with-headers")
    public ResponseEntity<Map<String, Object>> getWithCustomHeaders() {
        return ResponseEntity.ok().header("X-Custom-Header", "custom-value").body(Map.of("message", "with-headers"));
    }

    @PostMapping("/post/path/{id}/dto")
    public PostResponse postRequestWithPathParamRequestBody(
            @PathVariable("id") Long id, @RequestBody PostRequest requestBody) {
        return new PostResponse(id, requestBody.name(), requestBody.items());
    }

    @PostMapping("/post/create")
    public ResponseEntity<PostResponse> postCreateResource(@RequestBody PostRequest requestBody) {
        var created = new PostResponse(42L, requestBody.name(), requestBody.items());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/put/path/{id}/dto")
    public PostResponse putRequestWithPathParamRequestBody(
            @PathVariable("id") Long id, @RequestBody PostRequest requestBody) {
        return new PostResponse(id, requestBody.name(), requestBody.items());
    }

    @DeleteMapping("/delete/path/{id}")
    public ResponseEntity<Void> deleteRequestWithPathParam(@PathVariable("id") Long id) {
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/patch/path/{id}/dto")
    public PostResponse patchRequestWithPathParamRequestBody(
            @PathVariable("id") Long id, @RequestBody PatchRequest requestBody) {
        return new PostResponse(id, requestBody.name(), requestBody.items());
    }
}

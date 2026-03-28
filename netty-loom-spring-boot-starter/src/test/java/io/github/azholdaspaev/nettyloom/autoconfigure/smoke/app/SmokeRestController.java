package io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app;

import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.GetResponse;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.PostRequest;
import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.PostResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeRestController.CONTROLLER_PATH;

@RestController
@RequestMapping(CONTROLLER_PATH)
public class SmokeRestController {

    public static final String CONTROLLER_PATH = "/api/v1/rest";

    @GetMapping("/get/query/single")
    public Map<String, Object> getRequestWithSingleQueryParam(@RequestParam("value") String value) {
        return Collections.singletonMap("value", value);
    }

    @GetMapping("/get/query/multiple")
    public Map<String, Object> getRequestWithMultipleQueryParams(@RequestParam("first") String first,
                                                                 @RequestParam("second") Long second,
                                                                 @RequestParam("third") Integer third) {
        return Map.of("first", first, "second", second, "third", third);
    }

    @GetMapping("/get/query/response/dto")
    public GetResponse getRequestWithResponseDto(@RequestParam("id") Long id,
                                                 @RequestParam("name") String name,
                                                 @RequestParam("item") String item) {
        return new GetResponse(id, name, List.of(item));
    }

    @GetMapping("/get/path/{id}/dto")
    public GetResponse getRequestWithPathParamResponseDto(@PathVariable("id") Long id,
                                                          @RequestParam("name") String name,
                                                          @RequestParam("item") String item) {
        return new GetResponse(id, name, List.of(item));
    }

    @PostMapping("/post/path/{id}/dto")
    public PostResponse postRequestWithPathParamRequestBody(@PathVariable("id") Long id,
                                                            @RequestBody PostRequest requestBody) {
        return new PostResponse(id, requestBody.name(), requestBody.items());
    }
}

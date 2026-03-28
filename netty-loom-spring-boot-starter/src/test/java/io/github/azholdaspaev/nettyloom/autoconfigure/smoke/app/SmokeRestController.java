package io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app;

import io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto.GetResponse;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/query/single")
    public Map<String, Object> getRequestWithSingleQueryParam(@RequestParam("value") String value) {
        return Collections.singletonMap("value", value);
    }

    @GetMapping("/query/multiple")
    public Map<String, Object> getRequestWithMultipleQueryParams(@RequestParam("first") String first,
                                                                 @RequestParam("second") Long second,
                                                                 @RequestParam("third") Integer third) {
        return Map.of("first", first, "second", second, "third", third);
    }

    @GetMapping("/query/response/dto")
    public GetResponse getRequestWithResponseDto(@RequestParam("id") Long id,
                                                 @RequestParam("name") String name,
                                                 @RequestParam("item") String item) {
        return new GetResponse(id, name, List.of(item));
    }
}

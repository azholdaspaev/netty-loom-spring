package io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app;

import static io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeTestController.CONTROLLER_PATH;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(CONTROLLER_PATH)
public class SmokeTestController {

    public static final String CONTROLLER_PATH = "/api/v1/smore/test";

    @GetMapping
    public Map<String, Object> hello() {
        Thread current = Thread.currentThread();
        return Map.of(
                "message", "hello",
                "thread", current.getName(),
                "virtual", current.isVirtual(),
                "timestamp", Instant.now().toString());
    }

    @PostMapping("/echo")
    public TestDto echo(@RequestBody TestDto dto) {
        return dto;
    }

    public record TestDto(String name, int value) {}
}

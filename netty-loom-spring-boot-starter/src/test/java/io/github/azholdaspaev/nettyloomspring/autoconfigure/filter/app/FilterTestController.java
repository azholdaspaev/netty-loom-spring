package io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FilterTestController {

    @GetMapping("/api/greeting")
    public String greeting() {
        return "hello";
    }

    @GetMapping("/filtered/data")
    public String filtered() {
        return "filtered";
    }

    @GetMapping("/secure/data")
    public String secure() {
        return "secret";
    }

    @GetMapping("/boom/data")
    public String boom() {
        return "boom-reached";
    }
}

package io.azholdaspaev.nettyloom.autoconfigure.smoke.app;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SmokeController {

    @GetMapping(value = "/get")
    public String get() {
        return "Hello World";
    }
}

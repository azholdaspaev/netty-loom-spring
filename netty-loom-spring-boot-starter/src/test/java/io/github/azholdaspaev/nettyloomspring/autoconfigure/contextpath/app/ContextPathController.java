package io.github.azholdaspaev.nettyloomspring.autoconfigure.contextpath.app;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ContextPathController {

    @GetMapping("/hello")
    public String hello(HttpServletRequest request) {
        return request.getContextPath();
    }
}

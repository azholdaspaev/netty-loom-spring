package io.github.azholdaspaev.nettyloomspring.integration.app;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/form")
public class FormController {

    @GetMapping("/csrf")
    public String readCsrfToken(CsrfToken csrfToken) {
        return csrfToken.getToken();
    }

    @PostMapping("/echo")
    public String echoMessage(@RequestParam String message) {
        return message;
    }
}

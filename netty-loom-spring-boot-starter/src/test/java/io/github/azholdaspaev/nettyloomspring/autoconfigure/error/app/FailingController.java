package io.github.azholdaspaev.nettyloomspring.autoconfigure.error.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class FailingController {

    @GetMapping("/fail/exception")
    String uncaught() {
        throw new IllegalStateException("the handler blew up");
    }

    @GetMapping("/fail/send-error")
    void sendError(HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "no entry");
    }

    @GetMapping("/fail/gone")
    void gone(HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.GONE.value());
    }

    @GetMapping("/fail/with-session")
    String withSession(HttpServletRequest request) {
        request.getSession(true);
        throw new IllegalStateException("failed after creating a session");
    }

    @GetMapping("/gone-page")
    String gonePage() {
        return "the gone page";
    }

    @GetMapping("/secured/ping")
    String secured() {
        return "secured";
    }

    @GetMapping("/secured/denied")
    String denied() {
        return "denied";
    }
}

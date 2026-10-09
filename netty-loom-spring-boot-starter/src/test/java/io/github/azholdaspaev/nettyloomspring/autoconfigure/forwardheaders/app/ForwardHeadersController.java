package io.github.azholdaspaev.nettyloomspring.autoconfigure.forwardheaders.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class ForwardHeadersController {

    @GetMapping("/origin")
    String origin(HttpServletRequest request) {
        return String.join("|", request.getScheme(), String.valueOf(request.isSecure()), request.getServerName(),
            String.valueOf(request.getServerPort()), request.getRemoteAddr());
    }

    @GetMapping("/origin/redirect")
    void redirectToRequestUrl(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendRedirect(request.getRequestURL().toString());
    }

    @GetMapping("/origin/relative")
    void redirectRelative(HttpServletResponse response) throws IOException {
        response.sendRedirect("/origin");
    }

    @GetMapping("/origin/session")
    String session(HttpServletRequest request) {
        return request.getSession(true).getId();
    }
}

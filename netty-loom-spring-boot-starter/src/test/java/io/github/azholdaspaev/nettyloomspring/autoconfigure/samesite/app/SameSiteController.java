package io.github.azholdaspaev.nettyloomspring.autoconfigure.samesite.app;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SameSiteController {

    /** Written with no SameSite of its own, so only a supplier can put one on it. */
    @GetMapping("/same-site/tracked")
    public String tracked(HttpServletResponse response) {
        response.addCookie(new Cookie("tracker", "t"));
        return "tracked";
    }

    @GetMapping("/same-site/plain")
    public String plain(HttpServletResponse response) {
        response.addCookie(new Cookie("plain", "p"));
        return "plain";
    }

    @GetMapping("/same-site/session")
    public String session(HttpServletRequest request) {
        request.getSession(true);
        return "session";
    }
}

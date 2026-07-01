package io.github.azholdaspaev.nettyloomspring.autoconfigure.cookie.app;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.stream.Collectors;

@RestController
public class CookieController {

    @GetMapping("/cookie/read")
    public String read(@CookieValue("foo") String foo) {
        return foo;
    }

    @GetMapping("/cookie/read-all")
    public String readAll(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return "";
        }
        return Arrays.stream(cookies)
            .map(Cookie::getName)
            .collect(Collectors.joining(","));
    }

    @GetMapping("/cookie/set")
    public String set(HttpServletResponse response) {
        response.addCookie(new Cookie("sid", "xyz"));
        return "set";
    }

    @GetMapping("/cookie/echo")
    public String echo(@CookieValue(value = "sid", required = false) String sid) {
        return sid;
    }

    @GetMapping("/cookie/set-attrs")
    public String setAttrs(HttpServletResponse response) {
        Cookie cookie = new Cookie("sid", "xyz");
        cookie.setPath("/");
        cookie.setDomain("example.com");
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setMaxAge(3600);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        return String.join(",", response.getHeaders("Set-Cookie"));
    }
}

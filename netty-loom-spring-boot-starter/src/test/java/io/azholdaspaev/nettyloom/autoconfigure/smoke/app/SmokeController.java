package io.azholdaspaev.nettyloom.autoconfigure.smoke.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class SmokeController {

    @GetMapping(value = "/get")
    public String get() {
        return "Hello World";
    }

    @GetMapping("/api/greeting")
    public Greeting greeting() {
        return new Greeting("hello");
    }

    @GetMapping("/api/greeting/{name}")
    public Greeting greetingByName(@PathVariable String name) {
        return new Greeting("hello, " + name);
    }

    @GetMapping("/api/echo")
    public String echo(@RequestParam String msg) {
        return msg;
    }

    @GetMapping("/api/params")
    public Map<String, String[]> params(HttpServletRequest request) {
        return request.getParameterMap();
    }

    @GetMapping("/api/whoami")
    public String whoami(@RequestHeader("X-User") String user) {
        return user;
    }

    @GetMapping("/api/headers/date")
    public void dateHeader(HttpServletResponse response) {
        response.setDateHeader(HttpHeaders.LAST_MODIFIED, 0L);
    }

    @PostMapping("/api/greetings")
    public Greeting createGreeting(@RequestBody Greeting input) {
        return new Greeting("hello, " + input.message());
    }

    @PostMapping("/api/echo")
    public String echoForm(@RequestParam String msg) {
        return msg;
    }

    public record Greeting(String message) {
    }
}

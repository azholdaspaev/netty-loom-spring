package io.azholdaspaev.nettyloom.autoconfigure.smoke.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Locale;
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

    @PutMapping("/api/greetings/{name}")
    public Greeting replaceGreeting(@PathVariable String name, @RequestBody Greeting input) {
        return new Greeting(name + " says " + input.message());
    }

    @PutMapping("/api/greetings/{name}/ack")
    public ResponseEntity<Void> acknowledgeGreeting(@PathVariable String name) {
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/notes/{id}")
    public String replaceNote(@PathVariable String id, HttpServletRequest request) throws IOException {
        StringWriter body = new StringWriter();
        try (BufferedReader reader = request.getReader()) {
            reader.transferTo(body);
        }
        return body.toString().toUpperCase(Locale.ROOT);
    }

    @PatchMapping("/api/greetings/{name}")
    public Greeting patchGreeting(@PathVariable String name, @RequestBody Greeting input) {
        return new Greeting(name + " updated to " + input.message());
    }

    @PatchMapping("/api/echo")
    public String echoPatchForm(@RequestParam String msg) {
        return msg;
    }

    @PatchMapping("/api/greetings/{name}/touch")
    public ResponseEntity<Void> touchGreeting(@PathVariable String name) {
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/greetings/{name}")
    public ResponseEntity<Void> deleteGreeting(@PathVariable String name) {
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/greetings")
    public Greeting deleteGreetingsByPrefix(@RequestParam String prefix) {
        return new Greeting("deleted: " + prefix);
    }

    @DeleteMapping("/api/greetings/{name}/archive")
    public Greeting archiveGreeting(@PathVariable String name) {
        return new Greeting("archived: " + name);
    }

    public record Greeting(String message) {
    }
}

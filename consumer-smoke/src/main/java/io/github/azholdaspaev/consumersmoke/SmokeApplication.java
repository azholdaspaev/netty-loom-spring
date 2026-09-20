package io.github.azholdaspaev.consumersmoke;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class SmokeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmokeApplication.class, args);
    }

    @GetMapping("/smoke")
    public String reportServer(HttpServletRequest request) {
        return request.getServletContext().getServerInfo() + " " + request.getRequestURI();
    }
}

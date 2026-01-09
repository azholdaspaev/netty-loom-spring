package io.github.azholdaspaev.nettyloom.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Example Spring Boot application using Netty-Loom server.
 *
 * <p>This application demonstrates the Netty-Loom Spring Boot starter
 * with virtual thread support for benchmark comparison against Tomcat.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>/hello - Simple string response</li>
 *   <li>/json - JSON object serialization</li>
 *   <li>/db - Simulated blocking DB call (100ms)</li>
 *   <li>/mixed - Combined CPU work and IO delay</li>
 * </ul>
 */
@SpringBootApplication
public class ExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}

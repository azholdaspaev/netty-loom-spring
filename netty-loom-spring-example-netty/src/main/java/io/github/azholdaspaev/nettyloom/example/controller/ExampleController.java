package io.github.azholdaspaev.nettyloom.example.controller;

import io.github.azholdaspaev.nettyloom.example.service.SimulatedService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST controller with benchmark endpoints.
 *
 * <p>Provides endpoints for testing different workload types:
 * <ul>
 *   <li>/hello - Minimal overhead, string response</li>
 *   <li>/json - JSON serialization overhead</li>
 *   <li>/db - Blocking IO simulation</li>
 *   <li>/mixed - CPU + IO combined</li>
 * </ul>
 */
@RestController
public class ExampleController {

    private final SimulatedService simulatedService;

    public ExampleController(SimulatedService simulatedService) {
        this.simulatedService = simulatedService;
    }

    /**
     * Simple string response endpoint.
     * Measures minimal framework overhead.
     */
    @GetMapping("/hello")
    public String hello() {
        return "Hello World";
    }

    /**
     * JSON serialization endpoint.
     * Returns a moderately complex JSON object.
     */
    @GetMapping("/json")
    public Map<String, Object> json() {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "message", "JSON serialization benchmark",
                "data", List.of(
                        Map.of("id", 1, "name", "Item 1", "value", 100.50),
                        Map.of("id", 2, "name", "Item 2", "value", 200.75),
                        Map.of("id", 3, "name", "Item 3", "value", 300.25)
                ),
                "metadata", Map.of(
                        "version", "1.0",
                        "server", "netty-loom",
                        "thread", Thread.currentThread().getName(),
                        "virtual", Thread.currentThread().isVirtual()
                )
        );
    }

    /**
     * Simulated database call endpoint.
     * Tests handling of blocking IO operations.
     */
    @GetMapping("/db")
    public Map<String, Object> db() {
        return simulatedService.simulateDbCall();
    }

    /**
     * Mixed workload endpoint.
     * Combines CPU-intensive work with blocking IO.
     */
    @GetMapping("/mixed")
    public Map<String, Object> mixed() {
        return simulatedService.simulateMixedWorkload();
    }
}

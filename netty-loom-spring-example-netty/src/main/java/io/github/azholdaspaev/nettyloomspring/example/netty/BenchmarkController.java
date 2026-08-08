package io.github.azholdaspaev.nettyloomspring.example.netty;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identical to the Tomcat example's controller so the benchmark targets serve the same work.
 */
@RestController
public class BenchmarkController {

    private static final int WORK_MILLIS = 50;

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    @GetMapping("/work")
    public WorkResponse work() throws InterruptedException {
        Thread.sleep(WORK_MILLIS);
        return new WorkResponse("ok", WORK_MILLIS);
    }

    @GetMapping("/work-secured")
    public WorkResponse workSecured() throws InterruptedException {
        // Delegates rather than repeating the body: the benchmark's premise is that the secured and
        // unsecured endpoints do identical work, so the Δ between them is the filter chain alone.
        return work();
    }

    public record WorkResponse(String status, int sleptMillis) {
    }
}

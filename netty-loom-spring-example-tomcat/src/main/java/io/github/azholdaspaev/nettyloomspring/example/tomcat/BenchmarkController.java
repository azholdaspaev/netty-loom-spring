package io.github.azholdaspaev.nettyloomspring.example.tomcat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identical to the Netty example's controller so the three benchmark targets serve the same work.
 *
 * <ul>
 *   <li>{@code /ping} — minimal work, for low-concurrency throughput.</li>
 *   <li>{@code /work} — a blocking {@code Thread.sleep(50)} simulating a 50ms database call,
 *       the high-concurrency scenario where virtual threads should win.</li>
 *   <li>{@code /work-secured} — the same blocking call behind the Spring Security filter chain,
 *       so the delta against {@code /work} is the chain's cost. See {@link BenchmarkSecurityConfig}.</li>
 * </ul>
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

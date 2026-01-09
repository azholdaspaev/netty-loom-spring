package io.github.azholdaspaev.nettyloom.example.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service that simulates blocking operations for benchmarking.
 *
 * <p>This service provides methods that simulate real-world blocking scenarios:
 * <ul>
 *   <li>Database calls with network latency</li>
 *   <li>CPU-intensive computations</li>
 *   <li>Mixed workloads combining both</li>
 * </ul>
 */
@Service
public class SimulatedService {

    private static final long DB_LATENCY_MS = 100;

    /**
     * Simulates a blocking database call with 100ms latency.
     *
     * @return simulated database result
     */
    public Map<String, Object> simulateDbCall() {
        long startTime = System.currentTimeMillis();

        try {
            Thread.sleep(DB_LATENCY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DB call interrupted", e);
        }

        long actualLatency = System.currentTimeMillis() - startTime;

        return Map.of(
                "result", "database_record_123",
                "latency", actualLatency,
                "thread", Thread.currentThread().getName(),
                "virtual", Thread.currentThread().isVirtual()
        );
    }

    /**
     * Simulates CPU-intensive work by computing Fibonacci.
     *
     * @return computation result with timing info
     */
    public Map<String, Object> simulateCpuWork() {
        long startTime = System.currentTimeMillis();

        // Compute Fibonacci(35) - takes a few milliseconds
        long result = fibonacci(35);

        long duration = System.currentTimeMillis() - startTime;

        return Map.of(
                "computed", result,
                "duration", duration,
                "thread", Thread.currentThread().getName(),
                "virtual", Thread.currentThread().isVirtual()
        );
    }

    /**
     * Simulates a mixed workload combining CPU work and IO delay.
     *
     * @return combined result from both operations
     */
    public Map<String, Object> simulateMixedWorkload() {
        // Do CPU work first
        Map<String, Object> cpuResult = simulateCpuWork();

        // Then simulate IO
        Map<String, Object> dbResult = simulateDbCall();

        return Map.of(
                "computed", cpuResult.get("computed"),
                "cpuDuration", cpuResult.get("duration"),
                "fetched", dbResult.get("result"),
                "ioLatency", dbResult.get("latency"),
                "thread", Thread.currentThread().getName(),
                "virtual", Thread.currentThread().isVirtual()
        );
    }

    /**
     * Recursive Fibonacci computation.
     * Intentionally inefficient for CPU load simulation.
     */
    private long fibonacci(int n) {
        if (n <= 1) {
            return n;
        }
        return fibonacci(n - 1) + fibonacci(n - 2);
    }
}

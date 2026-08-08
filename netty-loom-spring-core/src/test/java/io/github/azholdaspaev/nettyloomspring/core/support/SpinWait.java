package io.github.azholdaspaev.nettyloomspring.core.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test helper for waiting on a condition another thread establishes. Centralizes the
 * nanoTime-deadline plus {@link Thread#onSpinWait()} idiom so tests do not each re-implement it.
 *
 * <p>Spinning rather than sleeping is the point: a sleep that loses makes the assertions after it
 * vacuous, and vacuous means green.
 */
public final class SpinWait {

    private SpinWait() {
    }

    /**
     * Spins until {@code condition} holds, failing the test with {@code message} if {@code limit} passes.
     */
    public static void until(BooleanSupplier condition, Duration limit, String message) {
        long deadlineNanos = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() - deadlineNanos < 0, message);
            Thread.onSpinWait();
        }
    }
}

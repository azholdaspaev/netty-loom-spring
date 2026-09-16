package io.github.azholdaspaev.nettyloomspring.core.handler;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationsTest {

    @Test
    void shouldConvertDurationWithinLongNanosExactly() {
        assertEquals(1_500_000_000L, Durations.toNanosSaturated(Duration.ofMillis(1_500)),
            "a duration that fits in long nanoseconds converts as Duration.toNanos does");
    }

    @Test
    void shouldSaturateAtLongMaxWhenDurationExceedsLongNanos() {
        assertEquals(Long.MAX_VALUE, Durations.toNanosSaturated(Duration.ofDays(200_000)),
            "a duration past Long.MAX_VALUE nanoseconds must saturate rather than throw from toNanos");
    }

    @Test
    void shouldFloorNegativeDurationAtZero() {
        assertEquals(0L, Durations.toNanosSaturated(Duration.ofDays(-200_000)),
            "a negative duration, however far below Long.MIN_VALUE nanoseconds, must floor at zero");
    }
}

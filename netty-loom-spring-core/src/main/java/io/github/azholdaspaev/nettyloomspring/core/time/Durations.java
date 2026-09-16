package io.github.azholdaspaev.nettyloomspring.core.time;

import java.time.Duration;

public final class Durations {

    // Duration.toNanos() is Math.multiplyExact and throws past Long.MAX_VALUE nanoseconds (#251, #326).
    private static final Duration MAX_NANOS = Duration.ofNanos(Long.MAX_VALUE);

    private Durations() {
    }

    public static long toNanosSaturated(Duration duration) {
        return duration.isNegative() ? 0L
            : duration.compareTo(MAX_NANOS) > 0 ? Long.MAX_VALUE
            : duration.toNanos();
    }
}

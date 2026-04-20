package io.azholdaspaev.nettyloom.core.server;

import java.time.Duration;

record Deadline(long nanoTime) {

    static Deadline in(Duration timeout) {
        return new Deadline(System.nanoTime() + timeout.toNanos());
    }

    long remainingMillis() {
        return Math.max(0L, (nanoTime - System.nanoTime()) / 1_000_000L);
    }
}

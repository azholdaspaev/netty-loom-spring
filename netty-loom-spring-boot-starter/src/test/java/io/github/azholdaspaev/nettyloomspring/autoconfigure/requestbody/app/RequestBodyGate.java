package io.github.azholdaspaev.nettyloomspring.autoconfigure.requestbody.app;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Hands each read the handler completes to the test, so a test can prove consumption is incremental
 * rather than assume it: the hand-off only completes once both sides meet, so a server that buffered
 * the whole request could never report the first read before the last byte was sent.
 */
@Component
public class RequestBodyGate {

    private static final Duration HANDOFF_LIMIT = Duration.ofSeconds(10);

    private final SynchronousQueue<Integer> reads = new SynchronousQueue<>();

    /** Called by the test; fails rather than hangs, which {@code @Timeout} cannot do for it. */
    public int awaitRead() throws InterruptedException {
        Integer read = reads.poll(HANDOFF_LIMIT.toSeconds(), TimeUnit.SECONDS);
        if (read == null) {
            throw new IllegalStateException(
                "the handler never reported a read, so the request body is not reaching it incrementally");
        }
        return read;
    }

    void reportRead(int bytes) throws InterruptedException {
        reads.offer(bytes, HANDOFF_LIMIT.toSeconds(), TimeUnit.SECONDS);
    }
}

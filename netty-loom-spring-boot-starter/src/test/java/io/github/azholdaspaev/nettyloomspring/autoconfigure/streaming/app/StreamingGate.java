package io.github.azholdaspaev.nettyloomspring.autoconfigure.streaming.app;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Holds the streaming handler between events so a test can prove delivery is incremental rather than
 * assume it: a hand-off only completes once the handler is actually waiting for the next one, so an
 * implementation that buffered the whole response could never reach the second event.
 */
@Component
public class StreamingGate {

    private static final Object RELEASE = new Object();

    private static final Duration HANDOFF_LIMIT = Duration.ofSeconds(10);

    private final SynchronousQueue<Object> releases = new SynchronousQueue<>();

    /**
     * Called by the test to let the handler produce one more event. Bounded rather than indefinite
     * because {@code @Timeout} cannot abort a test already stuck in a hand-off — it only reports once
     * the method returns — so the wait has to end itself.
     */
    public void release() throws InterruptedException {
        if (!releases.offer(RELEASE, HANDOFF_LIMIT.toSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                "the handler never came back for the next event, so the response is not being streamed");
        }
    }

    void awaitRelease() throws InterruptedException {
        releases.take();
    }
}

package io.github.azholdaspaev.nettyloomspring.autoconfigure.timeout.app;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Blocks for longer than the read timeout the tests configure. Blocking is the whole point of the
 * library, so this is an ordinary slow endpoint — a report, a slow query — not a pathological one.
 */
@RestController
public class SlowController {

    public static final String PATH = "/slow";

    /**
     * Must stay above the {@code server.netty.read-timeout} that {@code SlowHandlerNotTimedOutTest}
     * configures, or that regression gate passes vacuously. Not a ratio to trim for suite time: the gap
     * is headroom for the connect-to-request window, which spends the same budget.
     */
    public static final long DELAY_MILLIS = 1_500;

    @GetMapping(PATH)
    public String slow() throws InterruptedException {
        Thread.sleep(DELAY_MILLIS);
        return "slow";
    }
}

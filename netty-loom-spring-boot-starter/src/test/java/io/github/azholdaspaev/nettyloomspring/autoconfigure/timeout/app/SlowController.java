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

    /** Comfortably past the 500ms the timeout tests configure, without padding the suite further. */
    public static final long DELAY_MILLIS = 750;

    @GetMapping(PATH)
    public String slow() throws InterruptedException {
        Thread.sleep(DELAY_MILLIS);
        return "slow";
    }
}

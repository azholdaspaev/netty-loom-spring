package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Conversion of Boot's {@code server.servlet.session.timeout} to the session manager's unit (issue
 * #13). A pure function, so it is table-tested here rather than by booting an application per case.
 */
class NettyWebServerFactoryTimeoutTest {

    @Test
    void aPositiveTimeoutKeepsSecondResolution() {
        // Boot and Tomcat round to whole minutes because the ServletContext API speaks minutes; the
        // manager stores seconds, so the configured value survives as written.
        assertEquals(45, NettyWebServerFactory.timeoutSeconds(Duration.ofSeconds(45)));
        assertEquals(300, NettyWebServerFactory.timeoutSeconds(Duration.ofMinutes(5)));
    }

    @Test
    void aSubSecondTimeoutRoundsUpToOneSecond() {
        // Truncating to 0 would mean "never expires" -- the opposite of what was configured.
        assertEquals(1, NettyWebServerFactory.timeoutSeconds(Duration.ofMillis(500)));
    }

    @Test
    void zeroOrLessMeansNeverExpires() {
        assertEquals(0, NettyWebServerFactory.timeoutSeconds(Duration.ZERO));
        assertEquals(0, NettyWebServerFactory.timeoutSeconds(Duration.ofSeconds(-1)));
    }

    @Test
    void anUnsetTimeoutMeansNeverExpires() {
        assertEquals(0, NettyWebServerFactory.timeoutSeconds(null));
    }
}

package io.github.azholdaspaev.nettyloomspring.autoconfigure.port;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.ThrowableChains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.PortInUseException;

import java.net.BindException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Issue #74 compat gate: a taken port must fail with Boot's {@link PortInUseException}, the same way
 * Tomcat reports it, so {@code PortInUseFailureAnalyzer} can print the port and its remediation
 * instead of a bare "Failed to start Netty server".
 *
 * <p>Both tests search the cause chain rather than asserting the thrown type: the bind happens in
 * {@code WebServer.start()}, so the failure comes out of the lifecycle phase wrapped by
 * {@code DefaultLifecycleProcessor} in an {@code ApplicationContextException}. The failure analyzer
 * walks the chain too, which is why the user-facing message is unaffected.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ServerPortInUseTest {

    /**
     * The squatter holds the port for the whole attempt, so the collision is guaranteed and this
     * needs none of the retry budget {@link ServerPortBindingTest} carries for the opposite problem
     * (needing a port to stay <em>free</em> between selection and bind).
     */
    @Test
    void shouldReportTakenPortAsPortInUse() throws Exception {
        try (ServerSocket squatter = new ServerSocket(0)) {
            int takenPort = squatter.getLocalPort();

            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
                    .properties("server.port=" + takenPort)
                    .run());

            PortInUseException portInUse = ThrowableChains.findInChain(failure, PortInUseException.class);
            assertNotNull(portInUse, "startup failure should report port-in-use; was: " + failure);
            assertEquals(takenPort, portInUse.getPort());
        }
    }

    /**
     * The discrimination is Boot's, not ours: it matches only when the message contains "in use",
     * and {@code NettyServer#asBindFailure} carries the OS text through verbatim so that check has
     * something true to read.
     *
     * <p>192.0.2.1 is RFC 5737 TEST-NET-1 — reserved for documentation, so it is never a local
     * interface and the bind fails {@code EADDRNOTAVAIL} ("Can't assign requested address") on every
     * platform. Issue #74 proposed binding port 80 unprivileged for {@code EACCES} instead, but macOS
     * permits that bind, which would make this test pass vacuously there and leave a server on :80.
     */
    @Test
    void shouldNotReportUnassignableAddressAsPortInUse() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
            new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
                .properties("server.port=0", "server.address=192.0.2.1")
                .run());

        assertNotNull(ThrowableChains.findInChain(failure, BindException.class),
            "unassignable address should still fail the bind; was: " + failure);
        assertNull(ThrowableChains.findInChain(failure, PortInUseException.class),
            "a non-in-use bind failure must not be reported as port-in-use; was: " + failure);
    }
}

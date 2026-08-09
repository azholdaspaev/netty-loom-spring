package io.github.azholdaspaev.nettyloomspring.autoconfigure.port;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.ThrowableChains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.context.ConfigurableApplicationContext;

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
 * instead of a bare "Failed to start Netty server". The cause chain is searched rather than the thrown
 * type asserted: the bind happens in {@code WebServer.start()}, so the failure comes out of the
 * lifecycle phase wrapped by {@code DefaultLifecycleProcessor} in an {@code ApplicationContextException}.
 * The failure analyzer walks the chain too, which is why the user-facing message is unaffected.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ServerPortInUseTest {

    @Test
    void shouldReportTakenPortAsPortInUse() throws Exception {
        // Both sockets must bind the wildcard for the collision to be guaranteed: Netty enables
        // SO_REUSEADDR by default on every transport, and BSD lets a specific-address bind succeed over a
        // listening wildcard -- so setting server.address here would pass on Linux and fail on macOS.
        try (ServerSocket squatter = new ServerSocket(0)) {
            int takenPort = squatter.getLocalPort();

            RuntimeException failure = assertThrows(RuntimeException.class, () -> {
                try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
                    .properties("server.port=" + takenPort)
                    .run()) {
                    // Only reached if the bind unexpectedly succeeds; closing keeps that a legible
                    // single failure instead of leaking a live server into the rest of the suite.
                }
            });

            PortInUseException portInUse = ThrowableChains.findInChain(failure, PortInUseException.class);
            assertNotNull(portInUse, "startup failure should report port-in-use; was: " + failure);
            assertEquals(takenPort, portInUse.getPort());
        }
    }

    @Test
    void shouldNotReportUnassignableAddressAsPortInUse() {
        // 192.0.2.1 is RFC 5737 TEST-NET-1, reserved for documentation, so it is never a local interface
        // and the bind fails EADDRNOTAVAIL on every platform. Issue #74 proposed an unprivileged bind of
        // port 80 for EACCES instead, but macOS permits a wildcard bind there and would leave a live :80.
        RuntimeException failure = assertThrows(RuntimeException.class, () -> {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
                .properties("server.port=0", "server.address=192.0.2.1")
                .run()) {
                // Reachable where non-local binding is enabled (net.ipv4.ip_nonlocal_bind=1, or a
                // host that aliases TEST-NET-1); close so the failure stays a single legible one.
            }
        });

        assertNotNull(ThrowableChains.findInChain(failure, BindException.class),
            "unassignable address should still fail the bind; was: " + failure);
        assertNull(ThrowableChains.findInChain(failure, PortInUseException.class),
            "a non-in-use bind failure must not be reported as port-in-use; was: " + failure);
    }
}

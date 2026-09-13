package io.github.azholdaspaev.nettyloomspring.autoconfigure.session;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.ThrowableChains;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static io.github.azholdaspaev.nettyloomspring.autoconfigure.support.NettyLoomApplications.run;
import static io.github.azholdaspaev.nettyloomspring.autoconfigure.support.NettyLoomApplications.servletContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.servlet.session.*} must actually reach the container (issue #13). Boot has always bound
 * these properties onto the factory -- it is a {@code ConfigurableServletWebServerFactory} -- but until
 * sessions existed they were dropped on the floor.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SessionConfigurationTest {

    @Test
    void defaultTimeoutIsThirtyMinutes() {
        try (var context = run()) {
            assertEquals(30 * 60, servletContext(context).getSessionManager().getDefaultMaxInactiveInterval());
        }
    }

    @Test
    void aConfiguredTimeoutReachesTheSessionManager() {
        try (var context = run("server.servlet.session.timeout=45s")) {
            assertEquals(45, servletContext(context).getSessionManager().getDefaultMaxInactiveInterval());
            assertEquals(1, servletContext(context).getSessionTimeout(),
                "Reported through the minutes-based API it must round up, never down to 'never expires'");
        }
    }

    @Test
    void configuredCookiePropertiesReachTheCookieConfig() {
        try (var context = run(
            "server.servlet.session.cookie.name=SID",
            "server.servlet.session.cookie.path=/",
            "server.servlet.session.cookie.domain=example.test",
            "server.servlet.session.cookie.http-only=false",
            "server.servlet.session.cookie.max-age=60s")) {

            var config = servletContext(context).getSessionCookieConfig();
            assertEquals("SID", config.getName());
            assertEquals("/", config.getPath());
            assertEquals("example.test", config.getDomain());
            assertFalse(config.isHttpOnly());
            assertEquals(60, config.getMaxAge());
        }
    }

    @Test
    void configuredSameSiteReachesTheCookieConfig() {
        try (var context = run("server.servlet.session.cookie.same-site=lax")) {
            assertEquals("Lax", servletContext(context).getSessionCookieConfig().getAttribute("SameSite"));
        }
    }

    @Test
    void urlTrackingModeFailsStartupRatherThanBeingSilentlyIgnored() {
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> run("server.servlet.session.tracking-modes=url").close());

        assertTrue(ThrowableChains.chainMentions(failure, "server.servlet.session.tracking-modes"),
            "the failure should name the property to change; was: " + failure);
    }

    @Test
    void sessionPersistenceFailsStartupRatherThanBeingSilentlyIgnored() {
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> run("server.servlet.session.persistent=true").close());

        assertTrue(ThrowableChains.chainMentions(failure, "server.servlet.session.persistent"),
            "the failure should name the property to change; was: " + failure);
    }

    @Test
    void aDisabledPartitionedFlagIsNotEmitted() {
        /*
         * Boot maps this property through Object::toString, so `false` reaches the cookie config as the
         * string "false" -- which, stored verbatim, is *present* and would emit the flag it disables.
         */
        try (var context = run("server.servlet.session.cookie.partitioned=false")) {
            assertNull(servletContext(context).getSessionCookieConfig().getAttribute("Partitioned"));
        }
    }

    @Test
    void anEnabledPartitionedFlagIsEmitted() {
        try (var context = run("server.servlet.session.cookie.partitioned=true")) {
            assertEquals("", servletContext(context).getSessionCookieConfig().getAttribute("Partitioned"));
        }
    }

    @Test
    void theCookieConfigurationIsFrozenOnceTheContextHasStarted() {
        /*
         * The cookie name is read live on every request, so a runtime rename from any bean holding the
         * ServletContext would orphan every logged-in user. The spec requires the refusal.
         */
        try (var context = run()) {
            assertThrows(IllegalStateException.class,
                () -> servletContext(context).getSessionCookieConfig().setName("SID"));
        }
    }
}

package io.github.azholdaspaev.nettyloomspring.autoconfigure.session;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.server.SessionStoreLifecycle;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.DefaultNettyServletContext;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyHttpSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stop-phase session teardown (issue #13).
 *
 * <p>Moving the teardown out of bean destruction made it a {@code Lifecycle}, and a lifecycle can be
 * stopped and started again -- by {@code ApplicationContext.restart()}, by Actuator, and by CRaC
 * checkpoint/restore. A destroy callback never had to survive that, so the round trip is what these
 * pin: stopping must tear the store down, and starting must leave it able to serve.
 */
class SessionStoreLifecycleTest {

    private DefaultNettyServletContext servletContext;
    private SessionStoreLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        servletContext = new DefaultNettyServletContext();
        lifecycle = new SessionStoreLifecycle(servletContext);
        lifecycle.start();
    }

    @AfterEach
    void tearDown() {
        // Creating a session lazily starts a sweeper thread; closing is what stops it.
        servletContext.close();
    }

    @Test
    void stopExpiresTheLiveSessionsWhileTheApplicationBeansAreStillUp() {
        NettyHttpSession session = servletContext.getSessionManager().create();

        lifecycle.stop();

        assertFalse(lifecycle.isRunning());
        // Asserted through the servlet contract rather than the internal flag: an invalidated session is
        // exactly one whose accessors throw. A session outliving this phase would instead run its
        // @PreDestroy later, during bean destruction, against data sources that have already closed.
        assertThrows(IllegalStateException.class, () -> session.getAttribute("anything"),
            "stopping must invalidate the sessions it drops");
    }

    @Test
    void startingAgainAfterAStopLetsTheStoreServeSessions() {
        servletContext.getSessionManager().create();
        lifecycle.stop();

        lifecycle.start();

        assertTrue(lifecycle.isRunning());
        NettyHttpSession session = assertDoesNotThrow(() -> servletContext.getSessionManager().create(),
            "a restarted application must be able to create sessions again");
        assertSame(session, servletContext.getSessionManager().find(session.getId()));
    }

    @Test
    void aStopWithoutARestartLeavesTheStoreClosed() {
        // The other half of the pair: reopening must be something start() does, not something close()
        // forgot to do. Without this, "restart works" would also be satisfied by never closing at all.
        lifecycle.stop();

        assertThrows(IllegalStateException.class, () -> servletContext.getSessionManager().create());
    }

    @Test
    void theStoreIsNotPauseable() {
        // SmartLifecycle.isPauseable defaults to true, and pause() stops only pauseable beans. Left at
        // the default, a pause would skip the web server -- which keeps accepting requests -- while this
        // bean invalidated every session, logging every user out of an application still serving traffic.
        assertFalse(lifecycle.isPauseable(),
            "sessions may only be torn down when the server they belong to is going down too");
    }

    @Test
    void theStoreStopsAfterTheWebServerHasDrained() {
        // stopBeans sorts descending, so a lower phase stops later. Asserted as an inequality against
        // Boot's own constant rather than as an equality with our arithmetic, which would restate the
        // implementation instead of the ordering it exists to produce.
        assertTrue(lifecycle.getPhase() < WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE,
            "tearing sessions down before the server drains would hit requests still in flight");
    }
}

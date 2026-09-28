package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.DefaultNettyServletContext;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyHttpSession;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stop-phase servlet context teardown (issues #13 and #350).
 *
 * <p>Moving the teardown out of bean destruction made it a {@code Lifecycle}, and a lifecycle can be
 * stopped and started again -- by {@code ApplicationContext.restart()}, by Actuator, and by CRaC
 * checkpoint/restore. A destroy callback never had to survive that, so the round trip is what these
 * pin: stopping must close the servlet context, and starting must leave it able to serve.
 */
class ServletContextLifecycleTest {

    private DefaultNettyServletContext servletContext;
    private ServletContextLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        servletContext = new DefaultNettyServletContext();
        lifecycle = new ServletContextLifecycle(servletContext,
            new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)));
        lifecycle.start();
    }

    @AfterEach
    void tearDown() {
        // Creating a session lazily starts a sweeper thread; closing is what stops it.
        servletContext.close();
    }

    @Test
    void shouldExpireLiveSessionsOnStopWhileApplicationBeansAreUp() {
        NettyHttpSession session = servletContext.getSessionManager().newSession();

        lifecycle.stop();

        assertFalse(lifecycle.isRunning());
        /*
         * Asserted through the servlet contract rather than the internal flag: an invalidated session is
         * exactly one whose accessors throw.
         */
        assertThrows(IllegalStateException.class, () -> session.getAttribute("anything"),
            "stopping must invalidate the sessions it drops");
    }

    @Test
    void shouldServeSessionsFromStoreAfterStopThenStart() {
        servletContext.getSessionManager().newSession();
        lifecycle.stop();

        lifecycle.start();

        assertTrue(lifecycle.isRunning());
        NettyHttpSession session = assertDoesNotThrow(() -> servletContext.getSessionManager().newSession(),
            "a restarted application must be able to create sessions again");
        assertSame(session, servletContext.getSessionManager().find(session.getId()));
    }

    @Test
    void shouldLeaveServletContextClosedAfterStopWithoutRestart() {
        /*
         * The other half of the pair: reopening must be something start() does, not something close()
         * forgot to do. Without this, "restart works" would also be satisfied by never closing at all.
         */
        lifecycle.stop();

        assertThrows(IllegalStateException.class, () -> servletContext.getSessionManager().newSession());
    }

    @Test
    void shouldReportNotPauseable() {
        assertFalse(lifecycle.isPauseable(),
            "sessions may only be torn down when the server they belong to is going down too");
    }

    @Test
    void shouldStopServletContextAfterWebServerHasDrained() {
        /*
         * stopBeans sorts descending, so a lower phase stops later. Asserted as an inequality against
         * Boot's own constant rather than as an equality with our arithmetic, which would restate the
         * implementation instead of the ordering it exists to produce.
         */
        assertTrue(lifecycle.getPhase() < WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE,
            "tearing sessions down before the server drains would hit requests still in flight");
    }

    @Test
    void shouldAwaitCutOffDispatchesBeforeClosingServletContext() {
        NettyHttpSession session = servletContext.getSessionManager().newSession();
        session.setAttribute("state", "live");
        AtomicReference<Object> seenByDispatch = new AtomicReference<>();
        ServletContextLifecycle awaiting = startLifecycle(new HttpConnectionRegistry(
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)) {
            @Override
            public boolean interruptAndAwaitDispatches(long timeoutMillis) {
                seenByDispatch.set(session.getAttribute("state"));
                return true;
            }
        });

        awaiting.stop();

        assertEquals("live", seenByDispatch.get(),
            "a dispatch unwinding from the shutdown interrupt must still find its session");
        assertThrows(IllegalStateException.class, () -> session.getAttribute("state"),
            "the sessions must be dropped once the dispatches are done");
    }

    @Test
    void shouldMarkContextStoppingBeforeInterruptingDispatches() {
        AtomicBoolean stoppingOnInterrupt = new AtomicBoolean();
        ServletContextLifecycle awaiting = startLifecycle(new HttpConnectionRegistry(
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)) {
            @Override
            public boolean interruptAndAwaitDispatches(long timeoutMillis) {
                stoppingOnInterrupt.set(servletContext.isStopping());
                return true;
            }
        });

        awaiting.stop();

        assertTrue(stoppingOnInterrupt.get(),
            "a dispatch failing from the shutdown interrupt must find the context stopping, "
                + "or it is answered with an error page on a closed connection");
    }

    @Test
    void shouldCloseServletContextWhenDispatchesOutliveWait() {
        ServletContextLifecycle awaiting = startLifecycle(new HttpConnectionRegistry(
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)) {
            @Override
            public boolean interruptAndAwaitDispatches(long timeoutMillis) {
                return false;
            }
        });

        awaiting.stop();

        assertThrows(IllegalStateException.class, () -> servletContext.getSessionManager().newSession(),
            "a dispatch that ignores the interrupt must not hold the shutdown open");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldWarnWhenDispatchesOutliveWait(CapturedOutput output) {
        ServletContextLifecycle awaiting = startLifecycle(new HttpConnectionRegistry(
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)) {
            @Override
            public boolean interruptAndAwaitDispatches(long timeoutMillis) {
                return false;
            }
        });

        awaiting.stop();

        assertTrue(output.getAll().contains("WARN") && output.getAll().contains("still running"),
            "a handler that later fails on its closed session must be traceable to the shutdown; log was: "
                + output.getAll());
    }

    @Test
    void shouldCloseServletContextWhenDispatchWaitIsInterrupted() {
        ServletContextLifecycle awaiting = startLifecycle(new HttpConnectionRegistry(
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)) {
            @Override
            public boolean interruptAndAwaitDispatches(long timeoutMillis) throws InterruptedException {
                throw new InterruptedException();
            }
        });

        awaiting.stop();

        assertTrue(Thread.interrupted(), "the interrupt that cut the wait short must be restored");
        assertThrows(IllegalStateException.class, () -> servletContext.getSessionManager().newSession(),
            "an interrupted wait must still close the servlet context");
    }

    private ServletContextLifecycle startLifecycle(HttpConnectionRegistry registry) {
        ServletContextLifecycle started = new ServletContextLifecycle(servletContext, registry);
        started.start();
        return started;
    }
}

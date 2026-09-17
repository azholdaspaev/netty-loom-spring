package io.github.azholdaspaev.nettyloomspring.autoconfigure.listener;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.listener.app.InitOrderFilter;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.listener.app.ListenerTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.listener.app.RecordingListener;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.TimeUnit;

import static io.github.azholdaspaev.nettyloomspring.autoconfigure.support.NettyLoomApplications.servletContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code ServletContextListener} half of issue #17, which needs the application lifecycle itself
 * rather than a running server: the two events must stay balanced across shutdown and across the
 * stop/start cycle Spring replays on {@code ApplicationContext.start()}, {@code restart()} and CRaC
 * restore -- the same cycle {@code ServletContextLifecycle} already reopens the session store for.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ContextListenerLifecycleTest {

    private static ConfigurableApplicationContext run() {
        return new SpringApplicationBuilder(ListenerTestApplication.class)
            .properties("server.port=0")
            .run();
    }

    @Test
    void shouldFireInitializedOnceOnStartupAndDestroyedOnceOnClose() {
        RecordingListener listener;
        try (ConfigurableApplicationContext context = run()) {
            listener = context.getBean(RecordingListener.class);

            assertEquals(1, listener.countOf("contextInitialized"),
                "startup must deliver contextInitialized exactly once; saw " + listener.snapshot());
            assertEquals(0, listener.countOf("contextDestroyed"),
                "nothing may be destroyed while the application is running");
        }

        assertEquals(1, listener.countOf("contextDestroyed"),
            "closing the application must deliver contextDestroyed exactly once; saw " + listener.snapshot());
    }

    @Test
    void shouldRebalanceBothEventsAcrossStopStartCycle() {
        try (ConfigurableApplicationContext context = run()) {
            RecordingListener listener = context.getBean(RecordingListener.class);

            context.stop();
            assertEquals(1, listener.countOf("contextDestroyed"), listener.snapshot()::toString);

            context.start();
            assertEquals(2, listener.countOf("contextInitialized"),
                "a restarted context must re-initialize its listeners rather than serve with them torn "
                    + "down; saw " + listener.snapshot());
            assertEquals(1, listener.countOf("contextDestroyed"), listener.snapshot()::toString);
        }
    }

    @Test
    void shouldFireContextInitializedBeforeFilterInit() {
        try (ConfigurableApplicationContext context = run()) {
            assertTrue(context.getBean(InitOrderFilter.class).isContextInitializedBeforeInit(),
                "the spec notifies every ServletContextListener before any filter or servlet is initialized, "
                    + "so a Filter.init must already see contextInitialized; saw "
                    + context.getBean(RecordingListener.class).snapshot());
        }
    }

    @Test
    void shouldRefuseRegistrationOnceStartupHasFinished() {
        /*
         * The ServletContext.addListener contract: a listener registered from here on would never see
         * contextInitialized and would begin observing requests midway through the application's life.
         */
        try (ConfigurableApplicationContext context = run()) {
            NettyServletContext servletContext = servletContext(context);

            assertThrows(IllegalStateException.class,
                () -> servletContext.addListener(new RecordingListener()));
        }
    }
}

package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;

/**
 * Destroys the servlet and the filters, tears the session store down and fires
 * {@code contextDestroyed}, in the <em>stop</em> phase, while the application's beans are still
 * live. As a bean-destruction callback this would be the wrong phase: the servlet context is created
 * during {@code onRefresh()} and singletons are destroyed in reverse creation order, so it closes
 * after data sources have, leaving a {@code @SessionScope} bean's {@code @PreDestroy} to run against
 * a closed {@code DataSource}. Tomcat expires in {@code StandardManager.stopInternal()}, i.e. in this
 * phase, where the same callback succeeds. Here rather than in
 * {@code NettyServletWebServer.destroy()}, where Boot destroys Tomcat's servlet and filters: that
 * runs after bean destruction, so it would follow the {@code contextDestroyed} this phase fires --
 * the inversion issue #103 reports. The web server's stop phase has ended the drain before this
 * runs, but not the handler threads it cut off: {@link #stop()} interrupts them and waits up to two
 * seconds -- the {@code unloadDelay} Tomcat's {@code StandardWrapper.unload()} gives requests still
 * inside a servlet (tomcat-embed-core 11.0.20) -- so they unwind against live sessions (issue #89).
 */
public class ServletContextLifecycle implements SmartLifecycle {

    /**
     * Read from Boot's constant rather than hardcoded: were the number to move, this bean would land
     * on the wrong side of the server's stop phase with no compile error and no test failure.
     */
    private static final int PHASE = WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1;

    private static final long UNLOAD_DELAY_MILLIS = 2_000;

    private final NettyServletContext servletContext;

    private final HttpConnectionRegistry connectionRegistry;

    private volatile boolean running;

    public ServletContextLifecycle(NettyServletContext servletContext, HttpConnectionRegistry connectionRegistry) {
        this.servletContext = servletContext;
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    public void start() {
        /*
         * Reopened, not merely flagged: stop() closes the store for good otherwise. Spring restarts this
         * phase on ApplicationContext.start()/restart() and on CRaC restore -- and with Boot's documented
         * spring.context.checkpoint=onRefresh, the checkpoint/restore pair runs *during* refresh, so a
         * checkpointed application would reach its first request with a store that is already closed.
         */
        servletContext.open();
        this.running = true;
    }

    @Override
    public void stop() {
        this.running = false;
        boolean interrupted = false;
        try {
            connectionRegistry.interruptAndAwaitDispatches(UNLOAD_DELAY_MILLIS);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        servletContext.close();
        // Restored after close() rather than before, so the @PreDestroy callbacks it runs do not start interrupted.
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Never paused, mirroring {@code WebServerStartStopLifecycle}: {@code SmartLifecycle.isPauseable}
     * defaults to {@code true}, and a plain {@code pause()} stops only pauseable beans -- so at the
     * default it would invalidate every session while leaving the web server accepting requests.
     */
    @Override
    public boolean isPauseable() {
        return false;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}

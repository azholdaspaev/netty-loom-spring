package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;

/**
 * Tears the session store down in the <em>stop</em> phase, while the application's beans are still
 * live. As a bean-destruction callback this would be the wrong phase: the servlet context is created
 * during {@code onRefresh()} and singletons are destroyed in reverse creation order, so it closes
 * after data sources have, leaving a {@code @SessionScope} bean's {@code @PreDestroy} to run against
 * a closed {@code DataSource}. Tomcat expires in {@code StandardManager.stopInternal()}, i.e. in this
 * phase, where the same callback succeeds. Set {@code server.netty.shutdown-grace-period} strictly
 * below {@code spring.lifecycle.timeout-per-shutdown-phase}, which {@code LifecycleGroup.stop()}
 * gives up after, or the drain may still be running here (issue #89).
 */
public class SessionStoreLifecycle implements SmartLifecycle {

    /**
     * Read from Boot's constant rather than hardcoded: were the number to move, this bean would land
     * on the wrong side of the server's stop phase with no compile error and no test failure.
     */
    private static final int PHASE = WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1;

    private final NettyServletContext servletContext;

    private volatile boolean running;

    public SessionStoreLifecycle(NettyServletContext servletContext) {
        this.servletContext = servletContext;
    }

    @Override
    public void start() {
        // Reopened, not merely flagged: stop() closes the store for good otherwise. Spring restarts this
        // phase on ApplicationContext.start()/restart() and on CRaC restore -- and with Boot's documented
        // spring.context.checkpoint=onRefresh, the checkpoint/restore pair runs *during* refresh, so a
        // checkpointed application would reach its first request with a store that is already closed.
        servletContext.open();
        this.running = true;
    }

    @Override
    public void stop() {
        this.running = false;
        servletContext.close();
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

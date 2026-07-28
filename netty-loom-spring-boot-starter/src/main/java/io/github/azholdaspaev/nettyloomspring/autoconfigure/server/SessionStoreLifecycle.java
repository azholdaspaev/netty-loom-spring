package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;

/**
 * Tears the session store down in the <em>stop</em> phase, while the application's beans are still live.
 *
 * <p>Closing the servlet context is otherwise a bean-destruction callback, and that is the wrong phase.
 * {@code AbstractApplicationContext.doClose()} runs {@code lifecycleProcessor.onClose()} first and
 * {@code destroyBeans()} afterwards, and singletons are destroyed in reverse creation order -- the
 * servlet context is created during {@code onRefresh()}, before nearly every application bean, so it is
 * destroyed near the end, after data sources and connection pools have already closed. Expiring sessions
 * there means {@code DestructionCallbackBindingListener} fires a {@code @SessionScope} bean's
 * {@code @PreDestroy} against a closed {@code DataSource}. Tomcat expires in {@code
 * StandardManager.stopInternal()}, i.e. in this phase, where the same callback succeeds.
 *
 * <p>The phase sits one below {@code WebServerStartStopLifecycle}'s, so this stops <em>after</em> the
 * server has stopped accepting and drained: higher phases stop first. {@code close()} on the servlet
 * context remains as an idempotent backstop, and is the only thing that covers a failed startup, where
 * {@code onRefresh()} succeeded but {@code finishRefresh()} never ran.
 *
 * <p><strong>The drain is not guaranteed to have finished.</strong> {@code LifecycleGroup.stop()} waits
 * {@code spring.lifecycle.timeout-per-shutdown-phase} (30s by default) and then moves on regardless, and
 * {@code server.netty.shutdown-grace-period} also defaults to 30s -- so whenever a request is still in
 * flight at the deadline, Spring gives up on the server's phase before the drain reports done, and this
 * stop runs alongside servlet threads that are still executing. Set the grace period strictly below the
 * phase timeout for the ordering above to hold in that case; see issue #89.
 */
public class SessionStoreLifecycle implements SmartLifecycle {

    /**
     * One below {@code WebServerStartStopLifecycle}'s phase, so this stops just after it.
     *
     * <p>Read from Boot's own constant rather than restated: if the number ever moves, hardcoding it
     * would put this bean silently on the wrong side of the server's stop phase -- no compile error and
     * no test failure, just the destroy-phase teardown this class exists to prevent, quietly back.
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
     * Never paused, mirroring {@code WebServerStartStopLifecycle}, which returns {@code false} for the
     * same reason.
     *
     * <p>{@code SmartLifecycle.isPauseable} defaults to {@code true}, and a plain {@code pause()} stops
     * only pauseable beans. Left at the default, {@code pause()} would skip the web server -- which keeps
     * accepting requests -- while stopping this bean and invalidating every session, logging every user
     * out of an application that is still serving. Sessions may only be torn down when the server they
     * belong to is going down with them.
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

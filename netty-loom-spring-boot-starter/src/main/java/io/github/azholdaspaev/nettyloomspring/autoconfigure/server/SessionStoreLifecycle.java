package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
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
 */
public class SessionStoreLifecycle implements SmartLifecycle {

    /** {@code WebServerStartStopLifecycle.getPhase()} in Boot 4.0.5; one lower stops just after it. */
    private static final int WEB_SERVER_PHASE = Integer.MAX_VALUE - 2048;

    private final NettyServletContext servletContext;

    private volatile boolean running;

    public SessionStoreLifecycle(NettyServletContext servletContext) {
        this.servletContext = servletContext;
    }

    @Override
    public void start() {
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

    @Override
    public int getPhase() {
        return WEB_SERVER_PHASE - 1;
    }
}

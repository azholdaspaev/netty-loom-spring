package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeEvent;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The container-registered servlet listeners, and the one place that decides how each event is
 * delivered (issue #17).
 *
 * <p>One bucket per interface rather than a single list filtered per event: every {@code fire} below is
 * on a startup path or a per-request hot path, and a single list would mean an {@code instanceof} scan
 * over every listener the application registered on each of them.
 *
 * <p>{@code CopyOnWriteArrayList} rather than the {@code volatile}-snapshot idiom
 * {@link DefaultNettyServletContext} uses for filters. Registration is startup-only -- {@link
 * #markInitialized()} enforces that -- but the reads are per-request, and copy-on-write names its own
 * guard with no snapshot to invalidate and no chance of publishing a half-built list.
 *
 * <p>{@code HttpSessionActivationListener} and {@code AsyncListener} are deliberately absent: there is no
 * passivation (sessions are in-memory only) and no async support, so registering either would be
 * accepting a listener nothing could ever fire.
 */
public class NettyListenerRegistry {

    private static final Logger log = LoggerFactory.getLogger(NettyListenerRegistry.class);

    /**
     * The interfaces {@link #addListener} accepts. Held as a list purely so the rejection message can
     * name them -- the classification below tests each one directly, because a reflective dispatch would
     * buy nothing and cost the compiler's check that every accepted type has a bucket.
     */
    private static final List<Class<? extends EventListener>> SUPPORTED_TYPES = List.of(
        ServletContextListener.class, ServletContextAttributeListener.class,
        ServletRequestListener.class, ServletRequestAttributeListener.class,
        HttpSessionListener.class, HttpSessionAttributeListener.class, HttpSessionIdListener.class);

    private final List<ServletContextListener> contextListeners = new CopyOnWriteArrayList<>();
    private final List<ServletContextAttributeListener> contextAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<ServletRequestListener> requestListeners = new CopyOnWriteArrayList<>();
    private final List<ServletRequestAttributeListener> requestAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<HttpSessionListener> sessionListeners = new CopyOnWriteArrayList<>();
    private final List<HttpSessionAttributeListener> sessionAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<HttpSessionIdListener> sessionIdListeners = new CopyOnWriteArrayList<>();

    private final ServletContext servletContext;

    private volatile boolean initialized;

    NettyListenerRegistry(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    /**
     * Files a listener under every supported interface it implements.
     *
     * @throws IllegalArgumentException if it implements none, as {@code ServletContext.addListener}
     *                                  requires -- silently keeping it would leave the application
     *                                  believing it is wired up
     * @throws IllegalStateException    if the context has already been initialized
     */
    public void addListener(EventListener listener) {
        requireNotInitialized();
        // Non-short-circuiting |=, so a listener implementing several interfaces reaches every bucket.
        boolean supported = fileUnder(listener, ServletContextListener.class, contextListeners);
        supported |= fileUnder(listener, ServletContextAttributeListener.class, contextAttributeListeners);
        supported |= fileUnder(listener, ServletRequestListener.class, requestListeners);
        supported |= fileUnder(listener, ServletRequestAttributeListener.class, requestAttributeListeners);
        supported |= fileUnder(listener, HttpSessionListener.class, sessionListeners);
        supported |= fileUnder(listener, HttpSessionAttributeListener.class, sessionAttributeListeners);
        supported |= fileUnder(listener, HttpSessionIdListener.class, sessionIdListeners);
        if (!supported) {
            throw new IllegalArgumentException(listener.getClass().getName() + " implements none of the "
                + "listener interfaces netty-loom-spring fires: " + SUPPORTED_TYPES.stream()
                .map(Class::getName).toList());
        }
    }

    /**
     * Adds {@code listener} to {@code bucket} if it implements {@code type}, reporting whether it did.
     *
     * <p>{@code Class<T>} and {@code List<T>} share one type variable, so passing a bucket that does not
     * match its interface does not compile -- the same protection an {@code instanceof} chain gives,
     * without the seven near-identical blocks.
     */
    private <T extends EventListener> boolean fileUnder(EventListener listener, Class<T> type, List<T> bucket) {
        if (!type.isInstance(listener)) {
            return false;
        }
        bucket.add(type.cast(listener));
        return true;
    }

    /**
     * Freezes registration, as {@code ServletContext.addListener}'s {@code IllegalStateException} clause
     * requires. A listener registered after startup would never see {@code contextInitialized}, and on
     * the request side would begin observing mid-traffic -- both are bugs that are invisible without this.
     */
    public void markInitialized() {
        this.initialized = true;
    }

    private void requireNotInitialized() {
        if (initialized) {
            throw new IllegalStateException(
                "Listeners cannot be registered once the ServletContext has been initialized");
        }
    }

    // --- ServletContextListener ---

    void fireContextInitialized() {
        ServletContextEvent event = new ServletContextEvent(servletContext);
        // Every listener is initialized, and only then is the first failure rethrown, so a listener that
        // cannot initialize still aborts startup. Aborting the loop instead would be asymmetric with the
        // destroy pass, which walks the whole list: the startup backstop calls close() once getWebServer
        // fails, so a listener that never received contextInitialized would receive contextDestroyed and
        // tear down state it never built. Tomcat's listenerStart() records failure per listener for the
        // same reason. The exception leaves getWebServer uncaught and Boot reports it as an
        // ApplicationContextException.
        RuntimeException failure = null;
        for (ServletContextListener listener : contextListeners) {
            try {
                listener.contextInitialized(event);
            } catch (RuntimeException thrown) {
                if (failure == null) {
                    failure = thrown;
                } else {
                    // Attached rather than dropped, so one log names every listener that broke.
                    failure.addSuppressed(thrown);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    void fireContextDestroyed() {
        ServletContextEvent event = new ServletContextEvent(servletContext);
        fireQuietlyReversed(contextListeners, "ServletContextListener.contextDestroyed",
            listener -> listener.contextDestroyed(event));
    }

    // --- ServletContextAttributeListener ---
    //
    // Every fire*AttributeReplaced below takes `previous`, the value being displaced: a replacement
    // event reports the old value, not the new one. Callers decide which of added/replaced is due from
    // what their own put() displaced, so the event cannot disagree with the map they just wrote.

    void fireContextAttributeAdded(String name, Object value) {
        var event = new ServletContextAttributeEvent(servletContext, name, value);
        fireQuietly(contextAttributeListeners, "ServletContextAttributeListener.attributeAdded",
            listener -> listener.attributeAdded(event));
    }

    void fireContextAttributeReplaced(String name, Object previous) {
        var event = new ServletContextAttributeEvent(servletContext, name, previous);
        fireQuietly(contextAttributeListeners, "ServletContextAttributeListener.attributeReplaced",
            listener -> listener.attributeReplaced(event));
    }

    void fireContextAttributeRemoved(String name, Object value) {
        var event = new ServletContextAttributeEvent(servletContext, name, value);
        fireQuietly(contextAttributeListeners, "ServletContextAttributeListener.attributeRemoved",
            listener -> listener.attributeRemoved(event));
    }

    // --- ServletRequestListener ---
    //
    // Everything below is per-request, and the five methods here are the only ones that are: a bare
    // dispatch fires two request events plus ~16 attribute events, because DispatcherServlet publishes
    // its own context, locale resolver and matched handler as request attributes. Each therefore opens
    // with an isEmpty() check -- one volatile array read on a CopyOnWriteArrayList, no lock -- so an
    // application that registers no request listener allocates no event and no lambda at all. The
    // context and session events are startup- or per-session-scoped and need no such guard.

    public void fireRequestInitialized(ServletRequest request) {
        if (requestListeners.isEmpty()) {
            return;
        }
        ServletRequestEvent event = new ServletRequestEvent(servletContext, request);
        // Propagates, so the dispatcher's exception handling turns it into a status code: a listener that
        // failed to set up request scope has left the servlet unable to run correctly.
        requestListeners.forEach(listener -> listener.requestInitialized(event));
    }

    public void fireRequestDestroyed(ServletRequest request) {
        if (requestListeners.isEmpty()) {
            return;
        }
        ServletRequestEvent event = new ServletRequestEvent(servletContext, request);
        fireQuietlyReversed(requestListeners, "ServletRequestListener.requestDestroyed",
            listener -> listener.requestDestroyed(event));
    }

    // --- ServletRequestAttributeListener ---

    void fireRequestAttributeAdded(ServletRequest request, String name, Object value) {
        if (requestAttributeListeners.isEmpty()) {
            return;
        }
        var event = new ServletRequestAttributeEvent(servletContext, request, name, value);
        fireQuietly(requestAttributeListeners, "ServletRequestAttributeListener.attributeAdded",
            listener -> listener.attributeAdded(event));
    }

    void fireRequestAttributeReplaced(ServletRequest request, String name, Object previous) {
        if (requestAttributeListeners.isEmpty()) {
            return;
        }
        var event = new ServletRequestAttributeEvent(servletContext, request, name, previous);
        fireQuietly(requestAttributeListeners, "ServletRequestAttributeListener.attributeReplaced",
            listener -> listener.attributeReplaced(event));
    }

    void fireRequestAttributeRemoved(ServletRequest request, String name, Object value) {
        if (requestAttributeListeners.isEmpty()) {
            return;
        }
        var event = new ServletRequestAttributeEvent(servletContext, request, name, value);
        fireQuietly(requestAttributeListeners, "ServletRequestAttributeListener.attributeRemoved",
            listener -> listener.attributeRemoved(event));
    }

    // --- HttpSessionListener / HttpSessionIdListener ---

    void fireSessionCreated(HttpSession session) {
        HttpSessionEvent event = new HttpSessionEvent(session);
        // Quietly, not propagating: by the time this runs the session is already published in the store,
        // and an exception leaving here would abandon an entry that is still valid and whose id the
        // client never received -- nothing would invalidate or unbind it for the whole idle timeout.
        // A container-registered listener is also a bystander by this class's own test, unlike
        // valueBound, which belongs to the value being stored. Tomcat's tellNew() catches per listener.
        fireQuietly(sessionListeners, "HttpSessionListener.sessionCreated",
            listener -> listener.sessionCreated(event));
    }

    void fireSessionDestroyed(HttpSession session) {
        HttpSessionEvent event = new HttpSessionEvent(session);
        fireQuietlyReversed(sessionListeners, "HttpSessionListener.sessionDestroyed",
            listener -> listener.sessionDestroyed(event));
    }

    void fireSessionIdChanged(HttpSession session, String oldSessionId) {
        HttpSessionEvent event = new HttpSessionEvent(session);
        // Quietly: the rotation has already committed and the caller has yet to write the Set-Cookie, so
        // an exception leaving here strands the client on an id the store no longer knows -- a silent
        // logout that repeats for as long as the listener keeps failing. The listener is told about an
        // irreversible change it cannot refuse, which is this class's definition of a bystander.
        // Tomcat's StandardSession.tellChangedSessionId wraps each listener for the same reason.
        fireQuietly(sessionIdListeners, "HttpSessionIdListener.sessionIdChanged",
            listener -> listener.sessionIdChanged(event, oldSessionId));
    }

    // --- HttpSessionAttributeListener ---

    void fireSessionAttributeAdded(HttpSession session, String name, Object value) {
        var event = new HttpSessionBindingEvent(session, name, value);
        fireQuietly(sessionAttributeListeners, "HttpSessionAttributeListener.attributeAdded",
            listener -> listener.attributeAdded(event));
    }

    void fireSessionAttributeReplaced(HttpSession session, String name, Object previous) {
        var event = new HttpSessionBindingEvent(session, name, previous);
        fireQuietly(sessionAttributeListeners, "HttpSessionAttributeListener.attributeReplaced",
            listener -> listener.attributeReplaced(event));
    }

    void fireSessionAttributeRemoved(HttpSession session, String name, Object value) {
        var event = new HttpSessionBindingEvent(session, name, value);
        fireQuietly(sessionAttributeListeners, "HttpSessionAttributeListener.attributeRemoved",
            listener -> listener.attributeRemoved(event));
    }

    // --- Dispatch ---

    /**
     * Runs every listener, logging and continuing when one fails.
     *
     * <p>Used for teardown, where no caller is in a position to handle a failure, and for the attribute
     * events, which are observation rather than participation. That is not in tension with
     * {@code setAttribute} letting {@code valueBound} propagate: {@code HttpSessionBindingListener} is
     * the stored value's own resource protocol -- a failed bind leaves something half-acquired --
     * whereas an {@code *AttributeListener} is a bystander with nothing to unwind. Tomcat draws the same
     * line. The events that must propagate instead call {@code forEach} directly.
     */
    private static <T> void fireQuietly(List<T> listeners, String description, Consumer<T> callback) {
        for (T listener : listeners) {
            notify(listener, description, callback);
        }
    }

    /**
     * As {@link #fireQuietly}, but back to front: the spec reverses every destroy notification, so a
     * listener registered later -- and therefore potentially built on an earlier one -- tears down
     * before what it depends on.
     *
     * <p>Indexed rather than {@code List.reversed()}. On a {@code CopyOnWriteArrayList} that method is
     * not the free view it looks like: it returns a {@code Reversed} whose {@code DescendingIterator}
     * synchronizes on the list's write lock, so the per-request destroy path would allocate three
     * objects and touch a shared monitor. {@code size()} and {@code get(i)} are plain volatile array
     * reads.
     */
    private static <T> void fireQuietlyReversed(List<T> listeners, String description, Consumer<T> callback) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            notify(listeners.get(i), description, callback);
        }
    }

    private static <T> void notify(T listener, String description, Consumer<T> callback) {
        try {
            callback.accept(listener);
        } catch (RuntimeException failure) {
            // Per listener, not per event: one bad listener aborting the loop would silently skip
            // every listener registered after it, on every occurrence, for the life of the JVM.
            log.warn("{} failed on {}", description, listener.getClass().getName(), failure);
        }
    }
}

package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class NettyHttpSession implements HttpSession {

    private static final Logger log = LoggerFactory.getLogger(NettyHttpSession.class);

    private final NettySessionManager manager;
    private final long creationTime;
    // Unlike NettyHttpServletRequest's attributes, these really are shared: two browser tabs are two
    // concurrent requests on one session, each on its own virtual thread.
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    // CAS rather than a flag so a racing invalidate() reliably loses and throws, as the spec requires,
    // and so the sweeper can take the same transition silently.
    private final AtomicBoolean invalidated = new AtomicBoolean();

    private volatile String id;
    private volatile long lastAccessedTime;
    private volatile long thisAccessedTime;
    private volatile int maxInactiveInterval;
    private volatile boolean isNew = true;

    NettyHttpSession(String id, NettySessionManager manager, long now, int maxInactiveInterval) {
        this.id = id;
        this.manager = manager;
        this.creationTime = now;
        this.lastAccessedTime = now;
        this.thisAccessedTime = now;
        this.maxInactiveInterval = maxInactiveInterval;
    }

    /**
     * Records that a later request presented this session's id. A blind volatile write is enough:
     * concurrent requests race to store "now" and last-writer-wins is correct, so there is nothing for
     * a CAS to protect.
     */
    void access(long now) {
        this.lastAccessedTime = this.thisAccessedTime;
        this.thisAccessedTime = now;
        // Guarded because this only ever transitions once: a volatile read is a plain load, so every
        // request after the second skips the store fence.
        if (isNew) {
            this.isNew = false;
        }
    }

    boolean isExpired(long now) {
        int interval = maxInactiveInterval;
        return interval > 0 && now - thisAccessedTime >= interval * 1000L;
    }

    boolean isInvalidated() {
        return invalidated.get();
    }

    void setId(String id) {
        this.id = id;
    }

    /**
     * Expiry as driven by the store (lazily on lookup, or by the sweeper). Unlike {@link #invalidate()}
     * this is silent when the session is already gone, and does not call back into the manager -- the
     * caller has already evicted it.
     */
    void expire() {
        if (invalidated.compareAndSet(false, true)) {
            unbindAll();
        }
    }

    private void checkValid() {
        if (invalidated.get()) {
            throw new IllegalStateException("Session " + id + " has been invalidated");
        }
    }

    @Override
    public long getCreationTime() {
        checkValid();
        return creationTime;
    }

    @Override
    public String getId() {
        // Deliberately unguarded: Servlet 6.0 dropped the IllegalStateException so logging and audit
        // code can still name a session it has just destroyed.
        return id;
    }

    @Override
    public long getLastAccessedTime() {
        checkValid();
        return lastAccessedTime;
    }

    @Override
    public ServletContext getServletContext() {
        return manager.getServletContext();
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        this.maxInactiveInterval = interval;
    }

    @Override
    public int getMaxInactiveInterval() {
        return maxInactiveInterval;
    }

    @Override
    public Object getAttribute(String name) {
        checkValid();
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        checkValid();
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object value) {
        checkValid();
        if (value == null) {
            removeAttribute(name);
            return;
        }
        // Bound fires before the put, and its failure propagates: this runs inside a request, where the
        // application can still see and handle the error.
        if (value instanceof HttpSessionBindingListener listener) {
            listener.valueBound(new HttpSessionBindingEvent(this, name));
        }
        Object previous = attributes.put(name, value);
        // Re-binding the identical instance is not an unbind -- the value is still bound.
        if (previous != value && previous instanceof HttpSessionBindingListener listener) {
            notifyUnbound(listener, name);
        }
    }

    @Override
    public void removeAttribute(String name) {
        checkValid();
        if (attributes.remove(name) instanceof HttpSessionBindingListener listener) {
            notifyUnbound(listener, name);
        }
    }

    @Override
    public void invalidate() {
        if (!invalidated.compareAndSet(false, true)) {
            throw new IllegalStateException("Session " + id + " has already been invalidated");
        }
        manager.remove(this);
        unbindAll();
    }

    @Override
    public boolean isNew() {
        checkValid();
        return isNew;
    }

    private void unbindAll() {
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (entry.getValue() instanceof HttpSessionBindingListener listener) {
                notifyUnbound(listener, entry.getKey());
            }
        }
        attributes.clear();
        // HttpSessionListener.sessionDestroyed would fire here once addListener is supported (issue #17).
    }

    /**
     * Unbinding happens during teardown -- invalidation, timeout, a sweep -- where no caller is in a
     * position to handle a failure, so one bad listener must not strand the remaining values or kill
     * the sweeper thread.
     */
    private void notifyUnbound(HttpSessionBindingListener listener, String name) {
        try {
            listener.valueUnbound(new HttpSessionBindingEvent(this, name));
        } catch (RuntimeException e) {
            log.warn("HttpSessionBindingListener for session attribute '{}' failed on valueUnbound", name, e);
        }
    }
}

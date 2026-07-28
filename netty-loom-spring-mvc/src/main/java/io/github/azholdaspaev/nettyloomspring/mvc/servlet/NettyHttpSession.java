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
import java.util.concurrent.TimeUnit;
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
        return interval > 0 && now - thisAccessedTime >= TimeUnit.SECONDS.toMillis(interval);
    }

    boolean isInvalidated() {
        return invalidated.get();
    }

    void setId(String id) {
        this.id = id;
    }

    /**
     * Takes the one-way transition to invalid, reporting whether this caller made it. Split from
     * {@link #unbindAll()} so the manager can mark under the session's monitor -- which is what keeps a
     * concurrent lookup from returning a half-evicted session -- while running application listeners
     * outside it.
     */
    boolean markInvalidated() {
        return invalidated.compareAndSet(false, true);
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
        // Re-checked after the put, because the check at the top of this method can be overtaken: a
        // concurrent invalidate() may have already swept the map, in which case this value would sit in
        // a session nothing will ever tear down and would never receive valueUnbound.
        if (invalidated.get()) {
            removeIfStillBound(name, value);
            throw new IllegalStateException("Session " + id + " has been invalidated");
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
        // The mark and the store removal go together under the monitor, matching the manager's eviction
        // paths, so a concurrent lookup cannot resolve this session once either has begun.
        synchronized (this) {
            if (!markInvalidated()) {
                throw new IllegalStateException("Session " + id + " has already been invalidated");
            }
            manager.remove(this);
        }
        unbindAll();
    }

    @Override
    public boolean isNew() {
        checkValid();
        return isNew;
    }

    /**
     * Notifies and drops every bound value. Each entry is removed with the two-argument
     * {@code remove(key, value)} so exactly one caller can claim it: a plain iterate-then-clear lets
     * this and a concurrent {@code removeAttribute} both notify the same listener, which double-releases
     * whatever it was holding.
     */
    void unbindAll() {
        // A value bound by a request that raced this teardown may land after the iteration passes its
        // key; that request's own re-check in setAttribute removes and unbinds it, so nothing is left
        // silently bound and a second pass here would be redundant.
        attributes.forEach(this::removeIfStillBound);
        // HttpSessionListener.sessionDestroyed would fire here once addListener is supported (issue #17).
    }

    /** Removes {@code name} only if it still holds {@code value}, unbinding it if so. */
    private boolean removeIfStillBound(String name, Object value) {
        if (!attributes.remove(name, value)) {
            return false;
        }
        if (value instanceof HttpSessionBindingListener listener) {
            notifyUnbound(listener, name);
        }
        return true;
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

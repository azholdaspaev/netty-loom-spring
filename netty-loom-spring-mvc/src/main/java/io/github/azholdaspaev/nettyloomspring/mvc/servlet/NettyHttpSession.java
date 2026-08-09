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

    /**
     * Not {@code this}: {@code WebUtils.getSessionMutex} hands the session to application code as Spring's
     * session mutex, so locking on it would open an ABBA deadlock against the singleton lock.
     */
    private final Object lock = new Object();

    /**
     * Open only while {@link #unbindAll()} is notifying, so a listener can still read the session it is
     * being told about -- Tomcat's {@code expiring}, folded into {@code isValidInternal()}.
     */
    private volatile boolean destroying;

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
     * concurrent requests race to store "now" and last-writer-wins is correct.
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
     * {@link #unbindAll()} so the manager can mark under the session's monitor -- which keeps a concurrent
     * lookup from returning a half-evicted session -- while running application listeners outside it.
     */
    boolean markInvalidated() {
        return invalidated.compareAndSet(false, true);
    }

    /**
     * Whether anything is still bound. Readable after invalidation, unlike {@link #getAttributeNames()}.
     */
    boolean hasBoundAttributes() {
        return !attributes.isEmpty();
    }

    Object lock() {
        return lock;
    }

    private void checkValid() {
        if (invalidated.get() && !destroying) {
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
        // Reading what was already here, and publishing, are one step: compute records the displaced value
        // and decides publication from the invalidation flag while holding the bin lock, which unbindAll's
        // remove(key, value) also takes -- strictly after the CAS that sets that flag. So a key the
        // teardown has already claimed cannot be resurrected, and one it has not reached is either
        // published or left exactly as found. Reading the map beforehand instead let two requests binding
        // one instance each announce the single binding that resulted, and let a quiet re-bind resurrect a
        // value another thread had just released. No listener runs inside the mapping function -- that
        // would execute application code under a bin lock -- so every notification below is driven by what
        // compute recorded.
        var previous = new Object[1];
        var published = new boolean[1];
        attributes.compute(name, (key, existing) -> {
            previous[0] = existing;
            published[0] = !invalidated.get();
            return published[0] ? value : existing;
        });
        if (!published[0]) {
            // The teardown claimed this key first and the map was left as found, so nothing is announced.
            throw new IllegalStateException("Session " + id + " has been invalidated");
        }
        // Announced as soon as the map changed, and deliberately before every callback below: each runs
        // application code that may invalidate the session, and the claim-back that then follows notifies
        // attributeRemoved. Firing later would let that removal be the first a listener hears of this
        // value. This is why the order differs from Tomcat's, which unbinds the displaced value first.
        if (previous[0] == null) {
            manager.listeners().fireSessionAttributeAdded(this, name, value);
        } else {
            manager.listeners().fireSessionAttributeReplaced(this, name, previous[0]);
        }
        // Re-binding the identical instance is neither a bind nor an unbind. One comparison guarding both
        // sides keeps a listener that acquires in valueBound and releases in valueUnbound balanced;
        // Tomcat guards both too (notifyBindingListenerOnUnchangedValue is false).
        boolean bound = previous[0] != value;
        boolean invalid;
        try {
            // Unlike valueUnbound this propagates, but it can no longer veto the binding as Tomcat's does,
            // since the value is published by the time compute has told us a bind is owed. Hence the
            // finally: a thrown failure must not strand the displaced value, which nothing else can reach.
            if (bound && value instanceof HttpSessionBindingListener listener) {
                listener.valueBound(new HttpSessionBindingEvent(this, name, value));
            }
        } finally {
            if (bound && previous[0] instanceof HttpSessionBindingListener listener) {
                notifyUnbound(listener, name, previous[0]);
            }
            // Published, and only then invalidated: claim the value back, and if the teardown got there
            // first, remove(key, value) fails and it is that claim which notified. Read once, because
            // deciding the claim-back and the throw separately lets a flip between them throw without
            // taking the value back.
            invalid = invalidated.get();
            if (invalid) {
                removeIfStillBound(name, value);
            }
        }
        if (invalid) {
            throw new IllegalStateException("Session " + id + " has been invalidated");
        }
    }

    @Override
    public void removeAttribute(String name) {
        checkValid();
        Object removed = attributes.remove(name);
        if (removed != null) {
            notifyRemoved(name, removed);
        }
    }

    @Override
    public void invalidate() {
        // The mark and the store removal go together under the lock, matching the manager's eviction
        // paths, so a concurrent lookup cannot resolve this session once either has begun.
        synchronized (lock) {
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
     * {@code remove(key, value)} so exactly one caller can claim it: a plain iterate-then-clear lets this
     * and a concurrent {@code removeAttribute} both notify the same listener, which double-releases
     * whatever it was holding.
     */
    void unbindAll() {
        // Open across the whole teardown, not just the sessionDestroyed call: a valueUnbound implementation
        // commonly reads a sibling attribute to release what it is holding.
        destroying = true;
        try {
            // Before anything is unbound, as StandardSession.expire() does: a listener told the session
            // is going away can then still read what was in it.
            manager.listeners().fireSessionDestroyed(this);
            // A value bound by a request that raced this teardown may land after the iteration passes its
            // key; that request's own re-check in setAttribute removes and unbinds it, so nothing is left
            // silently bound and a second pass here would be redundant.
            attributes.forEach(this::removeIfStillBound);
        } finally {
            destroying = false;
        }
    }

    private boolean removeIfStillBound(String name, Object value) {
        if (!attributes.remove(name, value)) {
            return false;
        }
        notifyRemoved(name, value);
        return true;
    }

    /**
     * In Tomcat's order: the value's own {@code valueUnbound} first, then the container listeners'
     * {@code attributeRemoved}. Shared by {@code removeAttribute} and by teardown.
     */
    private void notifyRemoved(String name, Object value) {
        if (value instanceof HttpSessionBindingListener listener) {
            notifyUnbound(listener, name, value);
        }
        manager.listeners().fireSessionAttributeRemoved(this, name, value);
    }

    /**
     * Quiet: teardown has no caller in a position to handle a failure, and this also runs inside
     * {@link #setAttribute}'s {@code finally}, where throwing would discard the {@code valueBound} failure.
     */
    private void notifyUnbound(HttpSessionBindingListener listener, String name, Object value) {
        try {
            listener.valueUnbound(new HttpSessionBindingEvent(this, name, value));
        } catch (Throwable failure) {
            NettyListenerRegistry.rethrowIfFatal(failure);
            log.warn("HttpSessionBindingListener for session attribute '{}' failed on valueUnbound",
                name, failure);
        }
    }
}

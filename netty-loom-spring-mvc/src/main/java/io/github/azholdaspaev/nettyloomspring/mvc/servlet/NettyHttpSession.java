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
     * Serialises this session's store bindings -- rotation, removal, eviction and lookup.
     *
     * <p>Deliberately <em>not</em> {@code this}. {@code WebUtils.getSessionMutex} hands the session
     * instance itself to application code as Spring's session mutex whenever no
     * {@code HttpSessionMutexListener} has been registered, and {@code SessionScope} holds that mutex
     * across arbitrary bean instantiation. Locking on the session would put the sweeper and the
     * per-request lookup behind application code, and open an ABBA deadlock against the singleton lock.
     * An application may register that mutex listener to opt out, but few do, so this monitor is
     * load-bearing rather than belt-and-braces.
     */
    private final Object lock = new Object();

    /**
     * Open only while {@link #unbindAll()} is notifying, which is what keeps a destroyed session
     * readable for exactly as long as the listeners hearing about it need. Tomcat calls the same flag
     * {@code expiring} and folds it into {@code isValidInternal()} for the same reason: Spring
     * Security's {@code HttpSessionDestroyedEvent} walks {@code getAttributeNames()} to collect the
     * {@code SecurityContext}s it is publishing the logout for, and {@code HttpSessionBindingListener}s
     * commonly read siblings during {@code valueUnbound}.
     *
     * <p>Volatile, and visible to every thread rather than only the one tearing down -- also Tomcat's
     * behaviour. A request that resolved this session a moment earlier can therefore still read it
     * during the window instead of seeing {@code IllegalStateException}; it is a narrow widening of a
     * race that already existed, and the alternative is listeners that cannot do their job.
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

    /** Whether anything is still bound. Readable after invalidation, unlike {@link #getAttributeNames()}. */
    boolean hasBoundAttributes() {
        return !attributes.isEmpty();
    }

    /** The monitor every store binding is taken under. See the field's javadoc for why it is not {@code this}. */
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
        // Bound fires before the put, and its failure propagates: this runs inside a request, where the
        // application can still see and handle the error. Guarded on the value actually changing, and so
        // symmetric with the unbind below -- a listener that acquires in valueBound and releases in
        // valueUnbound would otherwise acquire once per re-bind and release once in total. Tomcat reads
        // the old value first for the same reason (notifyBindingListenerOnUnchangedValue is false).
        boolean bound = attributes.get(name) != value;
        if (bound && value instanceof HttpSessionBindingListener listener) {
            listener.valueBound(new HttpSessionBindingEvent(this, name, value));
        }
        // Published only if the session is still valid, and that decision is made *inside* compute so it
        // is atomic with the write. A plain put cannot be made to balance whatever the loser does next:
        // removing loudly afterwards double-unbinds a value the teardown already claimed, and removing
        // silently steals one it had not reached yet, so the original valueBound is never released.
        //
        // What makes this airtight is that compute holds the bin lock while it reads the flag, and
        // unbindAll's remove(key, value) takes that same lock -- while unbindAll strictly follows the CAS
        // that sets it. So if the teardown has already claimed this key, the flag is necessarily set by
        // the time compute looks, and the value cannot be resurrected into a session nothing will tear
        // down again. When invalidated, the map is left exactly as found rather than cleared: returning
        // null here would drop a binding the teardown still owes a valueUnbound for.
        //
        // No listener runs inside the mapping function -- that would execute application code while
        // holding a bin lock.
        var previous = new Object[1];
        var published = new boolean[1];
        attributes.compute(name, (key, existing) -> {
            previous[0] = existing;
            published[0] = !invalidated.get();
            return published[0] ? value : existing;
        });
        // Re-binding the identical instance is not an unbind -- the value is still bound.
        if (published[0] && previous[0] != value && previous[0] instanceof HttpSessionBindingListener listener) {
            notifyUnbound(listener, name, previous[0]);
        }
        if (!published[0]) {
            // The teardown claimed this key first, so it never saw this value. Anything *this* call bound
            // is therefore ours to release; a re-bind that fired no valueBound has nothing to pair with.
            if (bound && value instanceof HttpSessionBindingListener listener) {
                notifyUnbound(listener, name, value);
            }
            throw new IllegalStateException("Session " + id + " has been invalidated");
        }
        if (invalidated.get()) {
            // Published, and only then invalidated. Claim the value back -- and if the teardown got there
            // first, remove(key, value) fails and it is that claim, not this one, that notified.
            removeIfStillBound(name, value);
            throw new IllegalStateException("Session " + id + " has been invalidated");
        }
        // Only on the success path: every failure above throws.
        if (previous[0] == null) {
            manager.listeners().fireSessionAttributeAdded(this, name, value);
        } else {
            manager.listeners().fireSessionAttributeReplaced(this, name, previous[0]);
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
     * {@code remove(key, value)} so exactly one caller can claim it: a plain iterate-then-clear lets
     * this and a concurrent {@code removeAttribute} both notify the same listener, which double-releases
     * whatever it was holding.
     */
    void unbindAll() {
        // The window stays open across the whole teardown, not just the sessionDestroyed call: a
        // valueUnbound implementation commonly reads a sibling attribute to release what it is holding.
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

    /** Removes {@code name} only if it still holds {@code value}, notifying if so. */
    private boolean removeIfStillBound(String name, Object value) {
        if (!attributes.remove(name, value)) {
            return false;
        }
        notifyRemoved(name, value);
        return true;
    }

    /**
     * The two notifications an attribute leaving the session owes, in Tomcat's order: the value's own
     * {@code valueUnbound} first, then the container listeners' {@code attributeRemoved}. Shared by the
     * application's own {@code removeAttribute} and by teardown, so an audit listener sees the same event
     * either way.
     */
    private void notifyRemoved(String name, Object value) {
        if (value instanceof HttpSessionBindingListener listener) {
            notifyUnbound(listener, name, value);
        }
        manager.listeners().fireSessionAttributeRemoved(this, name, value);
    }

    /**
     * Unbinding happens during teardown -- invalidation, timeout, a sweep -- where no caller is in a
     * position to handle a failure, so one bad listener must not strand the remaining values or kill
     * the sweeper thread.
     */
    private void notifyUnbound(HttpSessionBindingListener listener, String name, Object value) {
        try {
            listener.valueUnbound(new HttpSessionBindingEvent(this, name, value));
        } catch (RuntimeException e) {
            log.warn("HttpSessionBindingListener for session attribute '{}' failed on valueUnbound", name, e);
        }
    }
}

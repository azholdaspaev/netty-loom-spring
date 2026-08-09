package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * In-memory store for {@link NettyHttpSession}, owning id generation and expiry (issue #13). Expiry is
 * enforced lazily on lookup -- {@link #find(String)} checks the deadline and evicts -- so the sweeper
 * exists only to reclaim sessions nothing looks up again, which would otherwise grow the heap without
 * bound. Distributed storage is out of scope; Spring Session's {@code SessionRepositoryFilter} replaces
 * {@code getSession} on a request wrapper and composes over this store without touching it.
 */
public class NettySessionManager {

    private static final Logger log = LoggerFactory.getLogger(NettySessionManager.class);

    private static final int DEFAULT_MAX_INACTIVE_INTERVAL_SECONDS = (int) TimeUnit.MINUTES.toSeconds(30);
    /**
     * Reclamation resolution only; expiry itself is exact, because every lookup checks the deadline.
     */
    private static final long SWEEP_INTERVAL_SECONDS = 60;
    private static final int SESSION_ID_BYTES = 16;
    /**
     * Bounds the shutdown drain; two passes suffice unless an entry is stranded, which is a bug.
     */
    private static final int MAX_SHUTDOWN_DRAIN_PASSES = 8;
    /**
     * The session cookie path for the root context, whose context path is the {@code ""} sentinel.
     */
    private static final String ROOT_COOKIE_PATH = "/";

    // One shared instance, deliberately: never getInstanceStrong() (it can block on /dev/random), and
    // never a ThreadLocal -- with one virtual thread per request that would mean an unbounded number of
    // SecureRandom instances. Contention here scales with the session-creation rate, not the request
    // rate, and on Java 25 a blocked monitor no longer pins the carrier thread (JEP 491).
    private static final SecureRandom ID_GENERATOR = new SecureRandom();
    // Hex, not Base64: ServerCookieEncoder.STRICT throws on octets outside the RFC 6265 cookie-value
    // set, and hex cannot produce one.
    private static final HexFormat ID_FORMAT = HexFormat.of().withUpperCase();

    // The sweeper's one thread. Named so it is identifiable in a thread dump or profiler rather than
    // appearing as an anonymous pool-N-thread-1, and a daemon so a manager that is never closed can
    // never keep the JVM alive. Platform rather than virtual: it is a long-lived timer that spends its
    // life parked, which is the case virtual threads do not help.
    private static final ThreadFactory SWEEPER_THREAD_FACTORY =
        Thread.ofPlatform().name("netty-loom-session-sweeper").daemon(true).factory();

    /**
     * URL rewriting is deliberately not supported, so cookie tracking is all this container implements.
     */
    private static final Set<SessionTrackingMode> SUPPORTED_TRACKING_MODES = Set.of(SessionTrackingMode.COOKIE);

    private final ConcurrentMap<String, NettyHttpSession> sessions = new ConcurrentHashMap<>();
    private final NettySessionCookieConfig cookieConfig = new NettySessionCookieConfig();
    // NettyServletContext, not ServletContext: the store fires HttpSessionListener events, and
    // getListenerRegistry() is on this seam rather than the Jakarta interface.
    private final NettyServletContext servletContext;
    private final LongSupplier clock;

    private volatile Set<SessionTrackingMode> trackingModes = SUPPORTED_TRACKING_MODES;
    private volatile int defaultMaxInactiveInterval = DEFAULT_MAX_INACTIVE_INTERVAL_SECONDS;
    private volatile ScheduledExecutorService sweeper;
    private volatile boolean contextInitialized;
    private volatile boolean closed;

    public NettySessionManager(NettyServletContext servletContext) {
        this(servletContext, System::currentTimeMillis);
    }

    NettySessionManager(NettyServletContext servletContext, LongSupplier clock) {
        this.servletContext = servletContext;
        this.clock = clock;
    }

    ServletContext getServletContext() {
        return servletContext;
    }

    NettyListenerRegistry listeners() {
        return servletContext.getListenerRegistry();
    }

    /**
     * The default idle timeout in <em>seconds</em>; zero or less means sessions never expire.
     */
    public int getDefaultMaxInactiveInterval() {
        return defaultMaxInactiveInterval;
    }

    public void setDefaultMaxInactiveInterval(int seconds) {
        requireContextNotInitialized();
        this.defaultMaxInactiveInterval = seconds;
    }

    /**
     * Applies a configured timeout, owning the "zero or less means never expires" conversion so no caller
     * restates it. Clamped, not cast: {@code toSeconds()} on a multi-century {@code Duration} overflows an
     * {@code int}, and the wrap lands on a plausible-looking small positive value.
     */
    public void setDefaultMaxInactiveInterval(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            setDefaultMaxInactiveInterval(0);
            return;
        }
        setDefaultMaxInactiveInterval((int) Math.clamp(timeout.toSeconds(), 1, Integer.MAX_VALUE));
    }

    public NettySessionCookieConfig getCookieConfig() {
        return cookieConfig;
    }

    /**
     * Signals that the owning {@code ServletContext} has finished initializing, freezing the session
     * cookie configuration as the {@code SessionCookieConfig} setters' {@code IllegalStateException}
     * clauses require.
     */
    public void markContextInitialized() {
        this.contextInitialized = true;
        cookieConfig.markInitialized();
    }

    /**
     * Enforces the {@code IllegalStateException} clause the {@code ServletContext} session setters share
     * with {@code SessionCookieConfig}'s. {@code setSessionTrackingModes} is the one that bites: the
     * tracking modes are read live on every request, so disabling cookie tracking at runtime would stop
     * issuing and reading session cookies from that moment, throwing every logged-in user into a login
     * loop with nothing logged anywhere.
     */
    void requireContextNotInitialized() {
        if (contextInitialized) {
            throw new IllegalStateException(
                "Sessions cannot be reconfigured once the ServletContext has been initialized");
        }
    }

    public Set<SessionTrackingMode> getDefaultTrackingModes() {
        return SUPPORTED_TRACKING_MODES;
    }

    public Set<SessionTrackingMode> getTrackingModes() {
        return trackingModes;
    }

    /**
     * Rejects anything but {@code COOKIE} rather than quietly ignoring it -- a silently dropped
     * {@code url} would leave sessions not working with no signal at all, so this fails fast the way
     * {@code NettyWebServerFactory} already does for {@code server.ssl.*}. An empty set is legal and
     * disables the session cookie.
     */
    public void setTrackingModes(Set<SessionTrackingMode> modes) {
        requireContextNotInitialized();
        for (SessionTrackingMode mode : modes) {
            if (!SUPPORTED_TRACKING_MODES.contains(mode)) {
                throw new IllegalArgumentException("Session tracking mode " + mode + " is not supported by "
                    + "netty-loom-spring; only COOKIE is. Remove it from server.servlet.session.tracking-modes.");
            }
        }
        this.trackingModes = Set.copyOf(modes);
    }

    private boolean isCookieTrackingEnabled() {
        return trackingModes.contains(SessionTrackingMode.COOKIE);
    }

    public NettyHttpSession create() {
        // Refused once closed rather than quietly stored: a request thread can still be in the
        // dispatcher while the context is being torn down, and a session added after the shutdown drain
        // would be dropped without ever being invalidated or unbound -- the client would hold a cookie
        // for a session no @PreDestroy will ever run against. Failing the request is the honest outcome.
        if (closed) {
            throw new IllegalStateException(
                "The servlet context has been closed; no new sessions can be created");
        }
        ensureSweeperStarted();
        long now = clock.getAsLong();
        String id = newSessionId();
        NettyHttpSession session = new NettyHttpSession(id, this, now, defaultMaxInactiveInterval);
        sessions.put(id, session);
        // Re-checked after the publish: the guard above is not atomic with this line -- ensureSweeperStarted
        // and newSessionId both take monitors in between -- so close() can run the whole drain while this
        // thread is between them, and a session published afterwards would sit in the store never
        // invalidated and never unbound.
        if (closed) {
            sessions.remove(id, session);
            session.markInvalidated();
            throw new IllegalStateException(
                "The servlet context has been closed; no new sessions can be created");
        }
        // Only once the session is reachable and the refusal window has passed: a listener told about a
        // session that is about to be dropped would hold a reference no teardown will ever revisit.
        listeners().fireSessionCreated(session);
        return session;
    }

    public NettyHttpSession find(String id) {
        if (id == null) {
            return null;
        }
        NettyHttpSession session = sessions.get(id);
        if (session == null) {
            return null;
        }
        long now = clock.getAsLong();
        // Under the session's monitor so eviction cannot complete between the liveness check and the
        // return: without it the sweeper can expire this session a moment after the check, and the
        // caller gets an object whose every accessor throws IllegalStateException.
        boolean expired;
        synchronized (session.lock()) {
            if (session.isInvalidated()) {
                return null;
            }
            if (!session.isExpired(now)) {
                session.access(now);
                return session;
            }
            expired = unbind(session);
        }
        if (expired) {
            session.unbindAll();
        }
        return null;
    }

    /**
     * Whether {@code id} names a live session, without touching its access time or freshness.
     */
    boolean isValidId(String id) {
        if (id == null) {
            return false;
        }
        NettyHttpSession session = sessions.get(id);
        return session != null && !session.isInvalidated() && !session.isExpired(clock.getAsLong());
    }

    /**
     * Rotates the session's id, as {@code HttpServletRequest.changeSessionId()} requires for
     * session-fixation protection (CWE-384, issue #52). Serialised on the session's monitor along with
     * every other path that unbinds it: the store is keyed by an id the session itself carries, so two
     * unserialised rotations would each bind a new id and both release the same old one, stranding an
     * entry under a key nothing can name again.
     */
    public String changeId(NettyHttpSession session) {
        String oldId;
        String newId;
        synchronized (session.lock()) {
            if (session.isInvalidated()) {
                throw new IllegalStateException("Session " + session.getId() + " has been invalidated");
            }
            oldId = session.getId();
            newId = newSessionId();
            // Bind before releasing, so a concurrent lookup never sees neither id.
            sessions.put(newId, session);
            session.setId(newId);
            sessions.remove(oldId, session);
        }
        // Outside the monitor: application code must never execute under a container lock, and Spring
        // Security rotates here on every authentication.
        listeners().fireSessionIdChanged(session, oldId);
        return newId;
    }

    void remove(NettyHttpSession session) {
        synchronized (session.lock()) {
            sessions.remove(session.getId(), session);
        }
    }

    /**
     * The read half of session-cookie tracking: the id the client presented, or {@code null}. Paired with
     * {@link #writeSessionCookie} so both directions read the same name and the same tracking-mode gate.
     * The client can legitimately present that name more than once -- RFC 6265 &sect;5.4 sends one pair
     * per stored cookie, cookies differing at all in Path or Domain are distinct, and &sect;5.4 orders
     * longest-path-first, so a stale {@code JSESSIONID} usually arrives first. Liveness therefore picks
     * among the candidates instead of the first match winning (issue #91); failing that the last one
     * stands, so {@code getRequestedSessionId()} still reports what the client sent, as Tomcat's
     * {@code CoyoteAdapter.parseSessionCookiesId} does.
     *
     * @param cookies the request's already-parsed cookies, in wire order, or {@code null} if it sent none
     */
    String readSessionId(Cookie[] cookies) {
        if (cookies == null || !isCookieTrackingEnabled()) {
            return null;
        }
        String name = cookieConfig.getName();
        String lastMatch = null;
        for (Cookie cookie : cookies) {
            // Case-sensitive, per RFC 6265 4.1.1: a name differing only in case is a different cookie,
            // and anything sharing the host can set one.
            if (!name.equals(cookie.getName())) {
                continue;
            }
            if (isValidId(cookie.getValue())) {
                return cookie.getValue();
            }
            lastMatch = cookie.getValue();
        }
        return lastMatch;
    }

    /**
     * Creates a session and hands the client its id. The check precedes the creation, so a refusal leaves
     * nothing in the store.
     */
    NettyHttpSession createAndTrack(NettyHttpServletResponse response, boolean secureConnection) {
        requireSessionCookieWritable(response);
        NettyHttpSession session = create();
        writeSessionCookie(response, session, secureConnection);
        return session;
    }

    /**
     * The Servlet contract: asked to create a session once the response is committed, a cookie-tracking
     * container throws {@code IllegalStateException}, and COOKIE is the only mode here.
     */
    private void requireSessionCookieWritable(HttpServletResponse response) {
        if (isCookieTrackingEnabled() && response.isCommitted()) {
            throw new IllegalStateException("Cannot create a session after the response has been "
                + "committed: the " + cookieConfig.getName() + " cookie can no longer be sent, so the "
                + "client would never retain the session. Call getSession() before sendRedirect/sendError.");
        }
    }

    /**
     * The write half: hands the client the id of a session just created or just rotated. Best-effort by
     * design -- the mandated {@code IllegalStateException} belongs to creation only, and
     * {@code changeSessionId} declares no such throw, so a late rotation behaves the way {@code addCookie}
     * does and simply has no effect, as it does on Tomcat.
     */
    void writeSessionCookie(NettyHttpServletResponse response, NettyHttpSession session, boolean secureConnection) {
        if (!isCookieTrackingEnabled()) {
            return;
        }
        Cookie cookie = new Cookie(cookieConfig.getName(), session.getId());
        // Both sides model attributes the way jakarta.servlet.http.Cookie does -- same names, same
        // case-insensitivity, same presence-encoding for flags -- so the whole configuration transfers
        // in one pass with nothing to fix up afterwards.
        cookieConfig.getAttributes().forEach(cookie::setAttribute);
        // Secure is the one attribute the request can strengthen: TLS forces it on regardless of
        // configuration (issue #16).
        if (secureConnection) {
            cookie.setSecure(true);
        }
        if (cookie.getPath() == null) {
            cookie.setPath(defaultCookiePath());
        }
        // Replace rather than append: a rotation within the same exchange would otherwise leave the
        // pre-rotation id as the first Set-Cookie of that name, and it has already been unbound from the
        // store. Browsers take last-wins, but anything reading the first header binds to a dead id.
        // Tomcat's addSessionCookieInternal does the same scan for the same reason.
        response.setCookie(cookie);
    }

    private String defaultCookiePath() {
        String contextPath = servletContext.getContextPath();
        return NettyServletContext.ROOT_CONTEXT_PATH.equals(contextPath) ? ROOT_COOKIE_PATH : contextPath;
    }

    /**
     * Reclaims every session past its deadline, returning how many. Deliberately not
     * {@code values().removeIf(...)}: each session is marked invalid as well as removed, so a request
     * thread that resolved it a moment earlier sees {@code IllegalStateException} rather than silently
     * writing into a map nothing reads.
     */
    int sweep(long now) {
        int reclaimed = 0;
        for (NettyHttpSession session : sessions.values()) {
            try {
                if (evictIfExpired(session, now)) {
                    reclaimed++;
                }
            } catch (Throwable failure) {
                // Per session, not per pass: ConcurrentHashMap iterates in a stable order, so one bad
                // session aborting the loop would skip everything ordered after it on every future pass
                // too, not just this one.
                log.warn("Failed to reclaim session {}", session.getId(), failure);
            }
        }
        return reclaimed;
    }

    /**
     * The running sweeper, or {@code null}. Package-private so a test can assert it was shut down.
     */
    ScheduledExecutorService sweeper() {
        return sweeper;
    }

    int size() {
        return sessions.size();
    }

    /**
     * Evicts only if still past the deadline as judged under the lock: {@code setMaxInactiveInterval} is
     * an unlocked volatile write, so an extension landing after an outside-the-lock test would be lost.
     */
    private boolean evictIfExpired(NettyHttpSession session, long now) {
        boolean marked;
        synchronized (session.lock()) {
            if (!session.isExpired(now)) {
                return false;
            }
            marked = unbind(session);
        }
        if (marked) {
            session.unbindAll();
        }
        return true;
    }

    /**
     * Removal and the invalid transition happen together under the lock, so {@link #find} cannot hand back
     * a session mid-eviction; the listener callbacks run outside it, being application code.
     */
    private void evict(NettyHttpSession session) {
        boolean marked;
        synchronized (session.lock()) {
            marked = unbind(session);
        }
        if (marked) {
            session.unbindAll();
        }
    }

    /**
     * Removes and marks invalid; caller must hold the session's monitor, and unbind values after.
     */
    private boolean unbind(NettyHttpSession session) {
        sessions.remove(session.getId(), session);
        return session.markInvalidated();
    }

    private static String newSessionId() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        ID_GENERATOR.nextBytes(bytes);
        return ID_FORMAT.formatHex(bytes);
    }

    /**
     * Lazy rather than eager, so an application that never calls {@code getSession(true)} -- and every
     * servlet-context instance in the test suite -- carries no background thread at all.
     */
    private void ensureSweeperStarted() {
        if (sweeper != null) {
            return;
        }
        synchronized (this) {
            if (sweeper != null || closed) {
                return;
            }
            ScheduledExecutorService started = Executors.newSingleThreadScheduledExecutor(SWEEPER_THREAD_FACTORY);
            started.scheduleWithFixedDelay(
                this::sweepQuietly, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
            sweeper = started;
        }
    }

    void sweepQuietly() {
        try {
            sweep(clock.getAsLong());
        } catch (Throwable failure) {
            // Throwable, not RuntimeException: scheduleWithFixedDelay cancels the task on anything that
            // escapes, silently and for the lifetime of the application. An application listener raising
            // NoClassDefFoundError from valueUnbound is enough, and reclamation stopping without a log
            // line is exactly the unbounded growth this class exists to prevent.
            log.warn("Session sweep failed", failure);
        }
    }

    /**
     * Reverses {@link #close()}, letting the store accept sessions again: Spring restarts the stop phase
     * on {@code ApplicationContext.start()}, {@code restart()} and CRaC restore, and without this the
     * store stays closed for the life of the JVM. The sweeper is deliberately not restarted here, since
     * {@code ensureSweeperStarted} is lazy and the next creation brings it back.
     */
    public void open() {
        synchronized (this) {
            closed = false;
        }
    }

    /**
     * Stops the sweeper and drops every session. Package-private deliberately: the manager is reachable
     * from any request handler via {@code ServletContext.getSessionManager()}, and wiping the store
     * belongs to whoever owns the context, not to request-handling code.
     */
    void close() {
        ScheduledExecutorService running;
        synchronized (this) {
            closed = true;
            running = sweeper;
            sweeper = null;
        }
        if (running != null) {
            running.shutdownNow();
        }
        // Expire rather than drop: Spring keeps a DestructionCallbackBindingListener as a session
        // attribute, so clearing silently here would mean no @SessionScope bean ever runs its destruction
        // callback on context close. Drained rather than iterate-then-clear: the map's iterator is weakly
        // consistent, so an entry inserted into a bin the loop has already passed would be missed and then
        // silently dropped by a trailing clear(). Bounded rather than trusting convergence, because the
        // failure mode of an unbounded drain is a JVM that never shuts down.
        for (int pass = 0; pass < MAX_SHUTDOWN_DRAIN_PASSES && !sessions.isEmpty(); pass++) {
            for (Map.Entry<String, NettyHttpSession> entry : sessions.entrySet()) {
                try {
                    evict(entry.getValue());
                } catch (Throwable failure) {
                    log.warn("Failed to expire session {} during shutdown", entry.getKey(), failure);
                }
                // By key, so a pass always makes progress even if the entry is bound under an id the
                // session no longer carries.
                sessions.remove(entry.getKey());
            }
        }
        if (!sessions.isEmpty()) {
            log.warn("{} session(s) could not be drained during shutdown", sessions.size());
            // Still marked on the way out: the loop giving up is a reason to stop retrying removal, not a
            // reason to abandon the invariant that a session leaving the store is invalid and has unbound
            // its values. Dropping them silently is the same missed @PreDestroy the drain above prevents.
            for (NettyHttpSession session : sessions.values()) {
                try {
                    if (session.markInvalidated()) {
                        session.unbindAll();
                    }
                } catch (Throwable failure) {
                    log.warn("Failed to expire session {} during shutdown", session.getId(), failure);
                }
            }
            sessions.clear();
        }
    }
}

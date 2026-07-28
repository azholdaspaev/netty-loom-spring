package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * In-memory store for {@link NettyHttpSession}, owning id generation and expiry (issue #13).
 *
 * <p>Expiry is enforced <em>lazily on lookup</em>: {@link #find(String)} checks the deadline and evicts,
 * so an expired session can never be handed back regardless of when the sweeper last ran. The sweeper
 * exists purely to reclaim memory for sessions nothing ever looks up again -- without it, traffic that
 * creates a session per visitor grows the heap without bound.
 *
 * <p>Distributed storage is out of scope; Spring Session's {@code SessionRepositoryFilter} replaces
 * {@code getSession} on a request wrapper and composes over this store without touching it.
 */
public class NettySessionManager {

    private static final Logger log = LoggerFactory.getLogger(NettySessionManager.class);

    private static final int DEFAULT_MAX_INACTIVE_INTERVAL_SECONDS = 30 * 60;
    /**
     * How often idle sessions are reclaimed. This is reclamation <em>resolution</em> only -- expiry
     * itself is exact, because every lookup checks the deadline.
     */
    private static final long SWEEP_INTERVAL_SECONDS = 60;
    private static final int SESSION_ID_BYTES = 16;
    /** The session cookie path for the root context, whose context path is the {@code ""} sentinel. */
    private static final String ROOT_COOKIE_PATH = "/";

    // One shared instance, deliberately: never getInstanceStrong() (it can block on /dev/random), and
    // never a ThreadLocal -- with one virtual thread per request that would mean an unbounded number of
    // SecureRandom instances. Contention here scales with the session-creation rate, not the request
    // rate, and on Java 25 a blocked monitor no longer pins the carrier thread (JEP 491).
    private static final SecureRandom ID_GENERATOR = new SecureRandom();
    // Hex, not Base64: ServerCookieEncoder.STRICT throws on octets outside the RFC 6265 cookie-value
    // set, and hex cannot produce one.
    private static final HexFormat ID_FORMAT = HexFormat.of().withUpperCase();

    private static final ThreadFactory SWEEPER_THREAD_FACTORY =
        Thread.ofPlatform().name("netty-loom-session-sweeper").daemon(true).factory();

    /** The only tracking mode this container implements; URL rewriting is deliberately not supported. */
    private static final Set<SessionTrackingMode> SUPPORTED_TRACKING_MODES = Set.of(SessionTrackingMode.COOKIE);

    private final ConcurrentMap<String, NettyHttpSession> sessions = new ConcurrentHashMap<>();
    private final NettySessionCookieConfig cookieConfig = new NettySessionCookieConfig();
    private final ServletContext servletContext;
    private final LongSupplier clock;

    private volatile Set<SessionTrackingMode> trackingModes = SUPPORTED_TRACKING_MODES;
    private volatile int defaultMaxInactiveInterval = DEFAULT_MAX_INACTIVE_INTERVAL_SECONDS;
    private volatile ScheduledExecutorService sweeper;
    private boolean closed;

    public NettySessionManager(ServletContext servletContext) {
        this(servletContext, System::currentTimeMillis);
    }

    NettySessionManager(ServletContext servletContext, LongSupplier clock) {
        this.servletContext = servletContext;
        this.clock = clock;
    }

    ServletContext getServletContext() {
        return servletContext;
    }

    /** The default idle timeout in <em>seconds</em>; zero or less means sessions never expire. */
    public int getDefaultMaxInactiveInterval() {
        return defaultMaxInactiveInterval;
    }

    public void setDefaultMaxInactiveInterval(int seconds) {
        this.defaultMaxInactiveInterval = seconds;
    }

    public NettySessionCookieConfig getCookieConfig() {
        return cookieConfig;
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
        ensureSweeperStarted();
        long now = clock.getAsLong();
        String id = newSessionId();
        NettyHttpSession session = new NettyHttpSession(id, this, now, defaultMaxInactiveInterval);
        sessions.put(id, session);
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
        if (session.isExpired(now)) {
            evict(session);
            return null;
        }
        session.access(now);
        return session;
    }

    /**
     * Rotates the session's id, as {@code HttpServletRequest.changeSessionId()} requires for
     * session-fixation protection (CWE-384, issue #52). Binds the new id before releasing the old so a
     * concurrent lookup never sees neither.
     */
    public String changeId(NettyHttpSession session) {
        String oldId = session.getId();
        String newId = newSessionId();
        session.setId(newId);
        sessions.put(newId, session);
        sessions.remove(oldId, session);
        return newId;
    }

    void remove(NettyHttpSession session) {
        sessions.remove(session.getId(), session);
    }

    /**
     * The read half of session-cookie tracking: the id the client presented, or {@code null}. Paired
     * with {@link #writeSessionCookie} so both directions read the same name and the same tracking-mode
     * gate; splitting them across classes is how the two drift.
     *
     * @param cookies the request's already-parsed cookies, or {@code null} if it sent none
     */
    String readSessionId(Cookie[] cookies) {
        if (cookies == null || !isCookieTrackingEnabled()) {
            return null;
        }
        String name = cookieConfig.getName();
        for (Cookie cookie : cookies) {
            // Cookie names are case-sensitive (RFC 6265). First match wins.
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** The write half: hands the client the id of a session just created or just rotated. */
    void writeSessionCookie(HttpServletResponse response, NettyHttpSession session, boolean secureConnection) {
        if (!isCookieTrackingEnabled()) {
            return;
        }
        if (response.isCommitted()) {
            log.warn("Session {} was created after the response was committed, so its cookie could not "
                + "be sent; the client will not retain this session", session.getId());
            return;
        }
        Cookie cookie = new Cookie(cookieConfig.getName(), session.getId());
        // Both sides model attributes the way jakarta.servlet.http.Cookie does -- same names, same
        // case-insensitivity, same presence-encoding for flags -- so the whole configuration transfers
        // in one pass with nothing to fix up afterwards.
        cookieConfig.getAttributes().forEach(cookie::setAttribute);
        // Secure is the one attribute the request can strengthen: TLS forces it on regardless of
        // configuration. Written this way, TLS support (issue #16) needs no change here.
        if (secureConnection) {
            cookie.setSecure(true);
        }
        if (cookie.getPath() == null) {
            cookie.setPath(defaultCookiePath());
        }
        response.addCookie(cookie);
    }

    private String defaultCookiePath() {
        String contextPath = servletContext.getContextPath();
        // The root context path is the "" sentinel, which would emit a meaningless empty Path attribute.
        return NettyServletContext.ROOT_CONTEXT_PATH.equals(contextPath) ? ROOT_COOKIE_PATH : contextPath;
    }

    /**
     * Reclaims every session past its deadline, returning how many. Package-private so tests exercise
     * the sweep without starting the thread.
     *
     * <p>Deliberately not {@code values().removeIf(...)}: each session is marked invalid as well as
     * removed, so a request thread that resolved it a moment earlier sees {@code IllegalStateException}
     * rather than silently writing into a map nothing reads.
     */
    int sweep(long now) {
        int reclaimed = 0;
        for (NettyHttpSession session : sessions.values()) {
            if (session.isExpired(now)) {
                evict(session);
                reclaimed++;
            }
        }
        return reclaimed;
    }

    int size() {
        return sessions.size();
    }

    private void evict(NettyHttpSession session) {
        sessions.remove(session.getId(), session);
        session.expire();
    }

    private static String newSessionId() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        ID_GENERATOR.nextBytes(bytes);
        return ID_FORMAT.formatHex(bytes);
    }

    /**
     * Starts the sweeper on first session creation. Lazy rather than eager so an application that never
     * calls {@code getSession(true)} -- and every servlet-context instance in the test suite -- carries
     * no background thread at all.
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

    private void sweepQuietly() {
        try {
            sweep(clock.getAsLong());
        } catch (RuntimeException e) {
            // scheduleWithFixedDelay cancels the task on a thrown exception; swallowing keeps one bad
            // session from stopping reclamation for the whole application.
            log.warn("Session sweep failed", e);
        }
    }

    /**
     * Stops the sweeper and drops every session. Package-private deliberately: the manager is reachable
     * from any request handler via {@code ServletContext.getSessionManager()}, and wiping the store is a
     * lifecycle operation that belongs to whoever owns the context, not to request-handling code.
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
        sessions.clear();
    }
}

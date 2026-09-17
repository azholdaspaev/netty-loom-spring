package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.azholdaspaev.nettyloomspring.mvc.servlet.UnknownSessionIds.OTHER_UNKNOWN_SESSION_ID;
import static io.github.azholdaspaev.nettyloomspring.mvc.servlet.UnknownSessionIds.UNKNOWN_SESSION_ID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session store behind {@code HttpServletRequest.getSession(...)} (issue #13). Every test drives an
 * injected clock rather than the wall clock, so expiry is asserted deterministically instead of via
 * {@code Thread.sleep}; {@code sweep(now)} is called directly, which is what the scheduled task runs.
 */
class NettySessionManagerTest {

    private static final int ONE_MINUTE = 60;
    private static final String MINTED_ID_SHAPE = "[0-9A-F]{32}";

    private AtomicLong clock;
    private DefaultNettyServletContext servletContext;
    private NettySessionManager manager;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0L);
        servletContext = new DefaultNettyServletContext();
        manager = new NettySessionManager(servletContext, clock::get);
        manager.setDefaultMaxInactiveInterval(ONE_MINUTE);
    }

    @AfterEach
    void tearDown() {
        // create() lazily starts the sweeper, so every test that makes a session owns a thread to stop.
        manager.close();
    }

    @Test
    void shouldDefaultMaxInactiveIntervalToThirtyMinutes() {
        NettySessionManager fresh = new NettySessionManager(new DefaultNettyServletContext(), clock::get);

        assertEquals(30 * 60, fresh.getDefaultMaxInactiveInterval(),
            "A container with no configuration should default to the servlet-conventional 30 minutes");
    }

    @Test
    void shouldGenerateDistinctUppercaseHexIdsOnCreate() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String id = manager.create().getId();
            assertTrue(id.matches(MINTED_ID_SHAPE),
                "Session id should be 32 uppercase hex characters (128 bits) but was '" + id + "'");
            assertTrue(ids.add(id), "Session ids must be distinct but '" + id + "' repeated");
        }
    }

    // --- Applying a configured timeout ---

    @Test
    void shouldKeepSecondResolutionForPositiveTimeout() {
        /*
         * Boot and Tomcat round to whole minutes because the ServletContext API speaks minutes; storing
         * seconds honours the configuration as written.
         */
        manager.setDefaultMaxInactiveInterval(Duration.ofSeconds(45));
        assertEquals(45, manager.getDefaultMaxInactiveInterval());

        manager.setDefaultMaxInactiveInterval(Duration.ofMinutes(5));
        assertEquals(300, manager.getDefaultMaxInactiveInterval());
    }

    @Test
    void shouldRoundSubSecondTimeoutUpToOneSecond() {
        // Truncating to 0 would mean "never expires" -- the opposite of what was configured.
        manager.setDefaultMaxInactiveInterval(Duration.ofMillis(500));

        assertEquals(1, manager.getDefaultMaxInactiveInterval());
    }

    @Test
    void shouldNeverExpireForTimeoutOfZeroOrLessOrUnset() {
        for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), null}) {
            manager.setDefaultMaxInactiveInterval(timeout);
            assertEquals(0, manager.getDefaultMaxInactiveInterval(), "for " + timeout);
        }
    }

    @Test
    void shouldClampImplausiblyLongTimeoutRatherThanWrap() {
        /*
         * toSeconds() on a multi-century Duration overflows an int, and the wrap lands on a plausible
         * small positive value: 100000 days would silently become ~579 days rather than never expiring.
         */
        manager.setDefaultMaxInactiveInterval(Duration.ofDays(100_000));

        assertEquals(Integer.MAX_VALUE, manager.getDefaultMaxInactiveInterval());
    }

    @Test
    void shouldInheritManagerDefaultIntervalOnCreatedSession() {
        assertEquals(ONE_MINUTE, manager.create().getMaxInactiveInterval());
    }

    @Test
    void shouldReturnCreatedSessionOnFind() {
        NettyHttpSession created = manager.create();

        assertSame(created, manager.find(created.getId()));
    }

    @Test
    void shouldReturnNullOnFindForUnknownId() {
        assertNull(manager.find("NOTASESSIONID"));
    }

    @Test
    void shouldExpireAndEvictSessionPastItsDeadlineOnFind() {
        NettyHttpSession created = manager.create();
        clock.set(ONE_MINUTE * 1000L);

        assertNull(manager.find(created.getId()), "A session idle for its full interval should not resolve");
        assertTrue(created.isInvalidated(),
            "An expired session must be marked invalid, not merely dropped: a request thread that "
                + "resolved it a moment earlier has to see IllegalStateException rather than write "
                + "into a detached map");
        assertEquals(0, manager.size(), "The expired session should have been evicted from the store");
    }

    @Test
    void shouldRefreshAccessTimeOnFindSoActiveSessionSurvives() {
        NettyHttpSession created = manager.create();

        /*
         * Just short of the deadline, then again a full interval later: the refresh in find() is what
         * keeps the second lookup alive.
         */
        clock.set(ONE_MINUTE * 1000L - 1);
        assertSame(created, manager.find(created.getId()));

        clock.set(ONE_MINUTE * 1000L * 2 - 2);
        assertSame(created, manager.find(created.getId()));
    }

    @Test
    void shouldClearIsNewOnFindSoSecondRequestSeesEstablishedSession() {
        NettyHttpSession created = manager.create();
        assertTrue(created.isNew(), "A session is new for the request that created it");

        manager.find(created.getId());

        assertFalse(created.isNew(), "Once a later request presents the id the session is no longer new");
    }

    @Test
    void shouldNeverExpireWithZeroInterval() {
        manager.setDefaultMaxInactiveInterval(0);
        NettyHttpSession created = manager.create();

        clock.set(Long.MAX_VALUE / 2);

        assertSame(created, manager.find(created.getId()),
            "A timeout of zero or less means the session never expires");
    }

    @Test
    void shouldNeverExpireWithNegativeInterval() {
        manager.setDefaultMaxInactiveInterval(-1);
        NettyHttpSession created = manager.create();

        clock.set(Long.MAX_VALUE / 2);

        assertSame(created, manager.find(created.getId()));
    }

    @Test
    void shouldSweepOnlyExpiredSessionsAndReturnReclaimedCount() {
        NettyHttpSession stale = manager.create();
        clock.set(ONE_MINUTE * 1000L - 1);
        NettyHttpSession active = manager.create();

        // Far enough that `stale` is past its deadline but `active` is not.
        long now = ONE_MINUTE * 1000L;
        assertEquals(1, manager.sweep(now), "Only the idle session should be reclaimed");
        assertEquals(1, manager.size());
        assertTrue(stale.isInvalidated());
        assertFalse(active.isInvalidated());
    }

    @Test
    void shouldReclaimNothingOnSweepWhenEverySessionIsActive() {
        manager.create();
        manager.create();

        assertEquals(0, manager.sweep(0L));
        assertEquals(2, manager.size());
    }

    @Test
    void shouldRemoveSessionFromStoreOnInvalidate() {
        NettyHttpSession created = manager.create();

        created.invalidate();

        assertNull(manager.find(created.getId()));
        assertEquals(0, manager.size());
    }

    @Test
    void shouldRebindSessionUnderNewIdAndReleaseOldOnChangeId() {
        NettyHttpSession created = manager.create();
        String oldId = created.getId();

        String newId = manager.changeId(created);

        assertNotEquals(oldId, newId, "changeSessionId must rotate the id (session fixation, issue #52)");
        assertEquals(newId, created.getId());
        assertSame(created, manager.find(newId), "The session must resolve under its new id");
        assertNull(manager.find(oldId), "The old id must no longer resolve");
        assertEquals(1, manager.size(), "Rotation rebinds one session, it does not create a second");
    }

    @Test
    void shouldPreserveSessionAttributesOnChangeId() {
        NettyHttpSession created = manager.create();
        created.setAttribute("user", "alice");

        manager.changeId(created);

        assertEquals("alice", created.getAttribute("user"));
    }

    @Test
    void shouldShutDownSweeperItStartedOnCloseIdempotently() {
        /*
         * Asserting on the executor, not just on size(): the eviction alone satisfies size() == 0, so
         * without this, deleting shutdownNow() would leave the suite green -- and a leaked sweeper is one
         * live platform thread per ApplicationContext, which is what @AfterEach here exists to prevent.
         */
        manager.create();
        var sweeper = manager.sweeper();
        assertNotNull(sweeper, "create() should have started the sweeper");

        manager.close();

        assertEquals(0, manager.size());
        assertTrue(sweeper.isShutdown(), "close() must shut down the sweeper it started");
        assertDoesNotThrow(manager::close);
    }

    @Test
    void shouldRefuseCreateAfterCloseAndStartNoSweeper() {
        /*
         * A request thread can still be in the dispatcher while the context is torn down. Storing that
         * session would leave it un-expired -- and resurrecting the sweeper would leak the thread.
         */
        manager.close();

        assertThrows(IllegalStateException.class, manager::create);
        assertNull(manager.sweeper(), "a closed manager must not start a background thread again");
    }

    @Test
    void shouldRefuseSessionPublishedAfterDrainNotLeaveItInStore() {
        /*
         * The guard at the top of create() is not atomic with its put -- ensureSweeperStarted and
         * newSessionId both take monitors in between -- so close() can run the whole drain while a
         * request thread sits mid-create, and the session lands in an already-drained store. It would
         * then never be invalidated or unbound, and no @SessionScope @PreDestroy would run for it: the
         * very guarantee the stop-phase teardown was introduced to provide.
         *
         * The window is opened deterministically rather than raced for. create() reads the clock between
         * its guard and its put, so an injected clock that closes the manager on the way through lands
         * exactly in the gap -- no threads, no timing, and the same code path a real loser takes.
         */
        var closed = new AtomicBoolean();
        var racing = new NettySessionManager[1];
        racing[0] = new NettySessionManager(new DefaultNettyServletContext(), () -> {
            if (closed.compareAndSet(false, true)) {
                racing[0].close();
            }
            return clock.get();
        });

        assertThrows(IllegalStateException.class, racing[0]::create,
            "a session that cannot be torn down must not be handed to a request either");
        assertEquals(0, racing[0].size(), "nothing may remain in the store once the drain has finished");
    }

    @Test
    void shouldLetStoreServeAgainOnOpenAfterStopStartCycle() {
        manager.close();
        manager.open();

        NettyHttpSession session = assertDoesNotThrow(manager::create);
        assertSame(session, manager.find(session.getId()));
        assertNotNull(manager.sweeper(), "a reopened store must be able to reclaim memory again");
    }

    @Test
    void shouldExpireSessionsRatherThanDropThemOnClose() {
        NettyHttpSession session = manager.create();

        manager.close();

        assertTrue(session.isInvalidated());
    }

    @Test
    void shouldTrackStoreWithoutTouchingSessionOnIsValidId() {
        NettyHttpSession session = manager.create();

        assertTrue(manager.isValidId(session.getId()));
        assertTrue(session.isNew(), "a validity query must not clear isNew");
        assertFalse(manager.isValidId("NOTASESSIONID"));
        assertFalse(manager.isValidId(null));

        session.invalidate();
        assertFalse(manager.isValidId(session.getId()));
    }

    @Test
    void shouldReportExpiredSessionInvalidBeforeAnySweepOnIsValidId() {
        NettyHttpSession session = manager.create();
        clock.set(ONE_MINUTE * 1000L);

        assertFalse(manager.isValidId(session.getId()));
    }

    @Test
    void shouldSwallowEvenErrorInScheduledSweep() {
        // An Error is what distinguishes this outer guard from catching only RuntimeException.
        var exploding = new NettySessionManager(new DefaultNettyServletContext(), () -> {
            throw new Error("clock exploded");
        });

        assertDoesNotThrow(exploding::sweepQuietly);

        exploding.close();
    }

    @Test
    void shouldSurviveSessionWhoseListenerThrowsOnSweep() {
        NettyHttpSession poisoned = manager.create();
        poisoned.setAttribute("bad", new HttpSessionBindingListener() {
            @Override
            public void valueUnbound(HttpSessionBindingEvent event) {
                throw new IllegalStateException("listener blew up");
            }
        });
        NettyHttpSession ordinary = manager.create();
        clock.set(ONE_MINUTE * 1000L);

        assertDoesNotThrow(() -> manager.sweep(clock.get()));

        assertTrue(ordinary.isInvalidated(), "a poisoned session must not strand the ones after it");
        assertEquals(0, manager.size());
    }

    // --- Reading the requested id from the request cookies (issue #91) ---

    private static final String SESSION_COOKIE = NettySessionCookieConfig.DEFAULT_NAME;

    /**
     * Cookies as the request presents them, in wire order: name, value, name, value...
     */
    private static Cookie[] cookies(String... nameValuePairs) {
        Cookie[] presented = new Cookie[nameValuePairs.length / 2];
        for (int i = 0; i < presented.length; i++) {
            presented[i] = new Cookie(nameValuePairs[2 * i], nameValuePairs[2 * i + 1]);
        }
        return presented;
    }

    @Test
    void shouldKeepUnknownSentinelsMintedShapedButNeverLive() {
        manager.create();

        for (String unknown : List.of(UNKNOWN_SESSION_ID, OTHER_UNKNOWN_SESSION_ID)) {
            assertTrue(unknown.matches(MINTED_ID_SHAPE),
                "the sentinel must have the shape create() mints, or a format check would reject it for the wrong reason: '"
                    + unknown + "'");
            assertFalse(manager.isValidId(unknown), "a well-formed id the store never minted must not be live");
        }
        assertNotEquals(UNKNOWN_SESSION_ID, OTHER_UNKNOWN_SESSION_ID,
            "the fallback-ordering tests assert which dead candidate is reported, so the two must differ");
    }

    @Test
    void shouldSkipStaleDuplicateAndReturnLiveIdOnReadSessionId() {
        /*
         * Issue #91: the stale duplicate routinely arrives first, and reading it hands find() an id that
         * is not in the store, so the user is logged out on every request.
         */
        NettyHttpSession live = manager.create();

        String resolved =
            manager.readSessionId(cookies(SESSION_COOKIE, UNKNOWN_SESSION_ID, SESSION_COOKIE, live.getId()));

        assertEquals(live.getId(), resolved, "a live duplicate must win over the stale one preceding it");
    }

    @Test
    void shouldKeepFirstLiveIdOverLaterStaleDuplicateOnReadSessionId() {
        NettyHttpSession live = manager.create();

        String resolved =
            manager.readSessionId(cookies(SESSION_COOKIE, live.getId(), SESSION_COOKIE, UNKNOWN_SESSION_ID));

        assertEquals(live.getId(), resolved);
    }

    @Test
    void shouldPreferFirstOfTwoLiveDuplicatesOnReadSessionId() {
        /*
         * Two live candidates, not one live beside a dead one: with a single live id "first live" and
         * "last live" select the same cookie, so the sibling tests pass against either (issue #99). The
         * tie-break is "first live", as Tomcat's CoyoteAdapter.parseSessionCookiesId replaces its
         * candidate only while isRequestedSessionIdValid() is false; preferring a later one would make
         * which session a request binds to depend on cookie order even when the first is usable.
         */
        NettyHttpSession first = manager.create();
        NettyHttpSession second = manager.create();

        String resolved =
            manager.readSessionId(cookies(SESSION_COOKIE, first.getId(), SESSION_COOKIE, second.getId()));

        assertEquals(first.getId(), resolved, "with two live duplicates the first must win, not the last");
    }

    @Test
    void shouldFallBackToLastMatchOnReadSessionIdWhenNoneIsLive() {
        /*
         * Nothing resolves, but an id must still be reported: SessionManagementFilter tells "presented an
         * expired id" from "presented none" purely by getRequestedSessionId() being non-null. Last rather
         * than first is Tomcat parity; which dead id it is cannot be observed, they are equally dead.
         */
        String resolved = manager.readSessionId(
            cookies(SESSION_COOKIE, UNKNOWN_SESSION_ID, SESSION_COOKIE, OTHER_UNKNOWN_SESSION_ID));

        assertEquals(OTHER_UNKNOWN_SESSION_ID, resolved);
    }

    @Test
    void shouldTreatExpiredDuplicateAsDeadOnReadSessionIdBeforeSweep() {
        /*
         * Liveness here is find()'s predicate, deadline included -- not mere presence in the store. The
         * sweeper runs on a delay, so an expired session stays mapped until it fires, and a
         * containsKey-shaped check would keep selecting it for that whole window.
         */
        NettyHttpSession expiring = manager.create();
        clock.set(ONE_MINUTE * 1000L - 1);
        NettyHttpSession live = manager.create();
        clock.set(ONE_MINUTE * 1000L);

        String resolved =
            manager.readSessionId(cookies(SESSION_COOKIE, expiring.getId(), SESSION_COOKIE, live.getId()));

        assertEquals(live.getId(), resolved, "an expired candidate must not mask a live one");
        assertEquals(2, manager.size(), "reading an id must evict nothing; expiry is find()'s job and the sweep's");
    }

    @Test
    void shouldSkipInvalidatedDuplicateOnReadSessionId() {
        // A logout in another tab leaves its cookie in the browser under whatever Path it was set with.
        NettyHttpSession invalidated = manager.create();
        NettyHttpSession live = manager.create();
        invalidated.invalidate();

        assertEquals(live.getId(),
            manager.readSessionId(cookies(SESSION_COOKIE, invalidated.getId(), SESSION_COOKIE, live.getId())),
            "an invalidated id must not mask the session that survived");
    }

    @Test
    void shouldNotTouchInspectedSessionsOnReadSessionId() {
        /*
         * The scan asks isValidId, not find, for the same reason isRequestedSessionIdValid() does: find()
         * refreshes the access time and clears isNew, so merely resolving the requested id would silently
         * age a session -- and mark it established -- before the application has asked for it.
         */
        NettyHttpSession live = manager.create();

        manager.readSessionId(cookies(SESSION_COOKIE, UNKNOWN_SESSION_ID, SESSION_COOKIE, live.getId()));

        assertTrue(live.isNew(), "resolving the requested id must not clear isNew");
    }

    @Test
    void shouldMatchCookieNameCaseSensitivelyOnReadSessionId() {
        /*
         * RFC 6265 4.1.1: cookie names are case-sensitive, so "jsessionid" is a different cookie -- one
         * anything sharing the host can set. Folding case would let it supply the session id, and with
         * liveness breaking the tie it would beat the real one outright.
         */
        NettyHttpSession live = manager.create();

        /*
         * Derived, not spelled out: a literal "jsessionid" would quietly stop being a case variant --
         * and this test would stop testing anything -- if the default name ever changed.
         */
        String miscased = SESSION_COOKIE.toLowerCase(Locale.ROOT);
        String resolved =
            manager.readSessionId(cookies(miscased, live.getId(), SESSION_COOKIE, UNKNOWN_SESSION_ID));

        assertEquals(UNKNOWN_SESSION_ID, resolved, "only the exactly-named cookie may be read as the session id");
    }

    @Test
    void shouldIgnoreCookiesOnReadSessionIdWhenTrackingIsDisabled() {
        NettyHttpSession live = manager.create();
        manager.setTrackingModes(Set.of());

        assertNull(manager.readSessionId(cookies(SESSION_COOKIE, live.getId())),
            "with COOKIE tracking off the container must not read a session id from one either");
    }

    @Test
    void shouldReturnNullOnReadSessionIdWhenNoCookieHasConfiguredName() {
        assertNull(manager.readSessionId(cookies("theme", "dark", "lang", "en")));
        assertNull(manager.readSessionId(null), "a request with no cookies at all presents no id");
    }

    @Test
    void shouldTreatEmptyDuplicateValueAsJustAnotherDeadCandidate() {
        // "JSESSIONID=" names no session, so it must lose the tie-break like any other dead candidate.
        NettyHttpSession live = manager.create();

        assertEquals(live.getId(),
            manager.readSessionId(cookies(SESSION_COOKIE, "", SESSION_COOKIE, live.getId())));
    }

    @Test
    void shouldSkipTrailingEmptyDuplicateOnReadSessionIdFallback() {
        String resolved = manager.readSessionId(cookies(SESSION_COOKIE, UNKNOWN_SESSION_ID, SESSION_COOKIE, ""));

        assertEquals(UNKNOWN_SESSION_ID, resolved, "an empty duplicate must not displace the id the client presented");
    }

    @Test
    void shouldReportEmptyIdOnReadSessionIdWhenOnlyEmptyIsPresented() {
        assertEquals("", manager.readSessionId(cookies(SESSION_COOKIE, "", SESSION_COOKIE, "")),
            "an empty value is skipped only in favour of another match, never dropped: alone it is the id the client presented (issue #94)");
    }

    @Test
    void shouldTolerateNullDuplicateValueOnReadSessionId() {
        assertNull(manager.readSessionId(cookies(SESSION_COOKIE, UNKNOWN_SESSION_ID, SESSION_COOKIE, null)),
            "a value a filter nulled through getCookies() must not throw; it stands as the last match, as before the empty guard");
    }

    // --- Container-registered session listeners (issue #17) ---

    /**
     * Records the ids each callback was handed, so a missed or duplicated event is visible.
     */
    private static final class RecordingSessionListener implements HttpSessionListener, HttpSessionIdListener {

        private final List<String> created = Collections.synchronizedList(new ArrayList<>());
        private final List<String> destroyed = Collections.synchronizedList(new ArrayList<>());
        private final List<String> idChanged = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void sessionCreated(HttpSessionEvent event) {
            created.add(event.getSession().getId());
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent event) {
            assertFalse(Thread.holdsLock(((NettyHttpSession) event.getSession()).lock()),
                "application code must never run under the container's session monitor");
            destroyed.add(event.getSession().getId());
        }

        @Override
        public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
            assertFalse(Thread.holdsLock(((NettyHttpSession) event.getSession()).lock()),
                "application code must never run under the container's session monitor");
            idChanged.add(oldSessionId + "->" + event.getSession().getId());
        }
    }

    private RecordingSessionListener recordSessions() {
        var listener = new RecordingSessionListener();
        servletContext.addListener(listener);
        return listener;
    }

    @Test
    void shouldFireSessionCreatedOnCreate() {
        var listener = recordSessions();

        NettyHttpSession session = manager.create();

        assertEquals(List.of(session.getId()), listener.created);
    }

    @Test
    void shouldPublishSessionBeforeFiringSessionCreated() {
        var resolved = new ArrayList<NettyHttpSession>();
        servletContext.addListener(new HttpSessionListener() {
            @Override
            public void sessionCreated(HttpSessionEvent event) {
                resolved.add(manager.find(event.getSession().getId()));
            }
        });

        NettyHttpSession session = manager.create();

        assertEquals(List.of(session), resolved, "a session registry resolves the session by id from "
            + "inside sessionCreated, so it must already be in the store when the listener runs");
    }

    @Test
    void shouldFireNothingWhenCreationIsRefusedAfterClose() {
        /*
         * create() refuses once the store is closed, and a session nothing can reach must not be
         * announced -- a listener told about it would hold a reference no teardown will ever revisit.
         */
        var listener = recordSessions();
        manager.close();

        assertThrows(IllegalStateException.class, () -> manager.create());

        assertTrue(listener.created.isEmpty());
    }

    @Test
    void shouldFireSessionDestroyedWhenLookupFindsExpiredSession() {
        var listener = recordSessions();
        NettyHttpSession session = manager.create();
        String id = session.getId();

        clock.set(TimeUnit.SECONDS.toMillis(ONE_MINUTE));

        assertNull(manager.find(id));
        assertEquals(List.of(id), listener.destroyed);
    }

    @Test
    void shouldFireSessionDestroyedForEverySessionSweepReclaims() {
        var listener = recordSessions();
        String first = manager.create().getId();
        String second = manager.create().getId();

        clock.set(TimeUnit.SECONDS.toMillis(ONE_MINUTE));
        manager.sweep(clock.get());

        assertEquals(Set.of(first, second), new HashSet<>(listener.destroyed));
    }

    @Test
    void shouldFireSessionDestroyedForEverySessionShutdownDrains() {
        /*
         * The shutdown drain is where a @SessionScope bean's destruction callback runs; a container
         * listener auditing logouts has exactly the same claim on being told.
         */
        var listener = recordSessions();
        String id = manager.create().getId();

        manager.close();

        assertEquals(List.of(id), listener.destroyed);
    }

    @Test
    void shouldFireSessionDestroyedOnceWhenSweepRacesLookup() {
        // Both paths funnel through the same markInvalidated() CAS, so only one of them may notify.
        var listener = recordSessions();
        NettyHttpSession session = manager.create();
        String id = session.getId();

        clock.set(TimeUnit.SECONDS.toMillis(ONE_MINUTE));
        manager.sweep(clock.get());
        assertNull(manager.find(id));
        manager.sweep(clock.get());

        assertEquals(List.of(id), listener.destroyed);
    }

    private static HttpSessionListener throwingOnCreate() {
        return new HttpSessionListener() {
            @Override
            public void sessionCreated(HttpSessionEvent event) {
                throw new IllegalStateException("listener is down");
            }
        };
    }

    @Test
    void shouldLeaveNoUnreachableSessionWhenCreatedListenerThrows() {
        /*
         * sessionCreated runs after sessions.put, so letting it propagate would abandon an entry that is
         * still valid and whose id no client ever received: nothing invalidates or unbinds it, and it
         * holds the store for the full idle timeout. Tomcat's tellNew() catches per listener and logs.
         */
        servletContext.addListener(throwingOnCreate());

        NettyHttpSession session = assertDoesNotThrow(() -> manager.create(),
            "a bystander listener must not fail the request that asked for the session");

        assertSame(session, manager.find(session.getId()), "the created session must be reachable by id");
        assertEquals(1, manager.size());
    }

    @Test
    void shouldNotStopLaterListenersWhenSessionCreatedListenerThrows() {
        var reached = new ArrayList<String>();
        servletContext.addListener(throwingOnCreate());
        servletContext.addListener(new HttpSessionListener() {
            @Override
            public void sessionCreated(HttpSessionEvent event) {
                reached.add("second");
            }
        });

        manager.create();

        assertEquals(List.of("second"), reached);
    }

    @Test
    void shouldFireSessionIdChangedWithOldIdOnChangeId() {
        var listener = recordSessions();
        NettyHttpSession session = manager.create();
        String oldId = session.getId();

        String newId = manager.changeId(session);

        assertEquals(List.of(oldId + "->" + newId), listener.idChanged);
    }
}

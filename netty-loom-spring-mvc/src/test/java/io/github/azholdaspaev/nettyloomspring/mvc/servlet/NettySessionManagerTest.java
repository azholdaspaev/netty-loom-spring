package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session store behind {@code HttpServletRequest.getSession(...)} (issue #13).
 *
 * <p>Every test drives an injected clock rather than the wall clock, so expiry is asserted
 * deterministically and instantly instead of via {@code Thread.sleep}. None of these tests start the
 * sweeper thread: {@code sweep(now)} is called directly, which is the same code the scheduled task runs.
 */
class NettySessionManagerTest {

    private static final int ONE_MINUTE = 60;

    private AtomicLong clock;
    private NettySessionManager manager;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0L);
        manager = new NettySessionManager(new DefaultNettyServletContext(), clock::get);
        manager.setDefaultMaxInactiveInterval(ONE_MINUTE);
    }

    @AfterEach
    void tearDown() {
        // create() lazily starts the sweeper, so every test that makes a session owns a thread to stop.
        manager.close();
    }

    @Test
    void defaultMaxInactiveIntervalIsThirtyMinutes() {
        NettySessionManager fresh = new NettySessionManager(new DefaultNettyServletContext(), clock::get);

        assertEquals(30 * 60, fresh.getDefaultMaxInactiveInterval(),
            "A container with no configuration should default to the servlet-conventional 30 minutes");
    }

    @Test
    void createGeneratesDistinctUppercaseHexIds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String id = manager.create().getId();
            // Hex, not Base64: ServerCookieEncoder.STRICT rejects octets outside the RFC 6265
            // cookie-value set, and hex cannot produce one.
            assertTrue(id.matches("[0-9A-F]{32}"),
                "Session id should be 32 uppercase hex characters (128 bits) but was '" + id + "'");
            assertTrue(ids.add(id), "Session ids must be distinct but '" + id + "' repeated");
        }
    }

    @Test
    void createdSessionInheritsTheManagerDefaultInterval() {
        assertEquals(ONE_MINUTE, manager.create().getMaxInactiveInterval());
    }

    @Test
    void findReturnsTheCreatedSession() {
        NettyHttpSession created = manager.create();

        assertSame(created, manager.find(created.getId()));
    }

    @Test
    void findReturnsNullForUnknownId() {
        assertNull(manager.find("NOTASESSIONID"));
    }

    @Test
    void findExpiresAndEvictsASessionPastItsDeadline() {
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
    void findRefreshesTheAccessTimeSoAnActiveSessionSurvives() {
        NettyHttpSession created = manager.create();

        // Just short of the deadline, then again a full interval later: the refresh in find() is what
        // keeps the second lookup alive.
        clock.set(ONE_MINUTE * 1000L - 1);
        assertSame(created, manager.find(created.getId()));

        clock.set(ONE_MINUTE * 1000L * 2 - 2);
        assertSame(created, manager.find(created.getId()));
    }

    @Test
    void findClearsIsNewSoASecondRequestSeesAnEstablishedSession() {
        NettyHttpSession created = manager.create();
        assertTrue(created.isNew(), "A session is new for the request that created it");

        manager.find(created.getId());

        assertFalse(created.isNew(), "Once a later request presents the id the session is no longer new");
    }

    @Test
    void zeroIntervalNeverExpires() {
        manager.setDefaultMaxInactiveInterval(0);
        NettyHttpSession created = manager.create();

        clock.set(Long.MAX_VALUE / 2);

        assertSame(created, manager.find(created.getId()),
            "A timeout of zero or less means the session never expires");
    }

    @Test
    void negativeIntervalNeverExpires() {
        manager.setDefaultMaxInactiveInterval(-1);
        NettyHttpSession created = manager.create();

        clock.set(Long.MAX_VALUE / 2);

        assertSame(created, manager.find(created.getId()));
    }

    @Test
    void sweepRemovesOnlyExpiredSessionsAndReturnsTheReclaimedCount() {
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
    void sweepReclaimsNothingWhenEverySessionIsActive() {
        manager.create();
        manager.create();

        assertEquals(0, manager.sweep(0L));
        assertEquals(2, manager.size());
    }

    @Test
    void invalidateRemovesTheSessionFromTheStore() {
        NettyHttpSession created = manager.create();

        created.invalidate();

        assertNull(manager.find(created.getId()));
        assertEquals(0, manager.size());
    }

    @Test
    void changeIdRebindsTheSessionUnderANewIdAndReleasesTheOld() {
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
    void changeIdPreservesSessionAttributes() {
        NettyHttpSession created = manager.create();
        created.setAttribute("user", "alice");

        manager.changeId(created);

        assertEquals("alice", created.getAttribute("user"));
    }

    @Test
    void closeIsIdempotentWhenTheSweeperNeverStarted() {
        manager.close();

        assertEquals(0, manager.size());
        manager.close();
    }
}

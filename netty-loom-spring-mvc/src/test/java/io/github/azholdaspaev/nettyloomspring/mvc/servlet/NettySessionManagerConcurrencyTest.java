package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Races on the session store (issue #13 review).
 *
 * <p>The store is keyed by an id the session itself carries and {@code changeId} mutates, so every
 * removal path has to agree on which key it is unbinding. These run many rounds because the windows
 * are narrow; each round is its own manager, so a single leaked entry fails the assertion.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NettySessionManagerConcurrencyTest {

    private static final int ROUNDS = 2_000;
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
        manager.close();
    }

    /** Runs both bodies on two threads released together, so they interleave rather than queue. */
    private static void race(Runnable first, Runnable second) throws InterruptedException {
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        for (Runnable body : new Runnable[] {first, second}) {
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    body.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException ignored) {
                    // A loser that throws IllegalStateException is a legitimate outcome here; what the
                    // assertions care about is the state the store is left in.
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish");
    }

    @Test
    void concurrentRotationsLeaveExactlyOneReachableEntry() throws InterruptedException {
        // Two tabs submitting the same login form both reach ChangeSessionIdAuthenticationStrategy.
        // Rotating on a key the session carries means an unserialised pair can bind two ids and unbind
        // only one, stranding an entry no removal path can ever name again.
        for (int round = 0; round < ROUNDS; round++) {
            NettyHttpSession session = manager.create();

            race(() -> manager.changeId(session), () -> manager.changeId(session));

            assertEquals(1, manager.size(),
                "round " + round + ": the store must hold one entry per live session, not one per rotation");
            assertTrue(manager.isValidId(session.getId()),
                "round " + round + ": the surviving entry must be reachable under the session's current id");
            session.invalidate();
            assertEquals(0, manager.size(), "round " + round + ": invalidate must unbind the surviving entry");
        }
    }

    @Test
    void aRotationRacingInvalidationLeavesNothingBehind() throws InterruptedException {
        // Login and logout in flight together. Whichever wins, an invalidated session must not remain
        // resolvable -- find() would keep refreshing it while every attribute access throws.
        for (int round = 0; round < ROUNDS; round++) {
            NettyHttpSession session = manager.create();

            race(() -> manager.changeId(session), session::invalidate);

            assertEquals(0, manager.size(), "round " + round + ": an invalidated session must leave no entry");
            assertNull(manager.find(session.getId()),
                "round " + round + ": an invalidated session must not resolve under any id");
        }
    }

    @Test
    void aValueBoundWhileTheSessionIsTornDownIsStillUnbound() throws InterruptedException {
        // The re-check in setAttribute exists for exactly this: checkValid() can pass and invalidation
        // land during the put, leaving a value in a session nothing will ever tear down. The <= 1 half
        // is what holds the remove(key, value) claim honest against a double release.
        for (int round = 0; round < ROUNDS; round++) {
            NettyHttpSession session = manager.create();
            var unbound = new AtomicInteger();
            var value = new HttpSessionBindingListener() {
                @Override
                public void valueUnbound(HttpSessionBindingEvent event) {
                    unbound.incrementAndGet();
                }
            };

            race(() -> session.setAttribute("k", value), session::invalidate);

            assertTrue(session.isInvalidated(), "round " + round + ": invalidate must win eventually");
            assertFalse(session.hasBoundAttributes(),
                "round " + round + ": teardown must leave no attribute bound");
            assertTrue(unbound.get() <= 1,
                "round " + round + ": a value must be unbound at most once, got " + unbound.get());
        }
    }

    @Test
    void findNeverHandsBackASessionTheSweeperHasExpired() throws InterruptedException {
        // The session must still be live when the race starts, and the *sweeping* thread must be the one
        // that advances the clock -- otherwise find() arrives after the deadline has already passed,
        // always takes its own eviction branch, and the test asserts nothing about the race at all.
        for (int round = 0; round < ROUNDS; round++) {
            NettyHttpSession session = manager.create();
            String id = session.getId();
            long deadline = clock.get() + ONE_MINUTE * 1000L;

            var resolved = new NettyHttpSession[1];
            race(() -> resolved[0] = manager.find(id),
                () -> {
                    clock.set(deadline);
                    manager.sweep(deadline);
                });

            // Deliberately not "the returned session is still usable": once find() has returned, the
            // sweeper may legitimately expire the session an instant later, so that assertion fails for
            // a correct implementation. What must hold is that the store and the session never disagree
            // -- nothing invalidated may remain reachable, and nothing reachable may be invalidated.
            assertEquals(0, manager.size(), "round " + round + ": the expired session must be gone");
            assertNull(manager.find(id), "round " + round + ": an evicted session must not resolve");
            assertTrue(session.isInvalidated(),
                "round " + round + ": a session removed from the store must have been marked invalid");
            clock.set(deadline);
        }
    }

    @Test
    void aSessionExtendedWhileTheSweeperWaitsForTheLockIsNotEvicted() throws InterruptedException {
        // The widest form of the window, made deterministic by holding the lock: the sweeper judges the
        // session expired, blocks entering the eviction, and an ordinary setMaxInactiveInterval -- a
        // plain volatile write, taking no lock -- extends it before the sweeper gets in. Deciding expiry
        // outside the lock and then evicting unconditionally destroys a session that is now live for
        // another hour, and the request that extended it sees IllegalStateException on its next access.
        NettyHttpSession session = manager.create();
        long deadline = ONE_MINUTE * 1000L;
        clock.set(deadline);

        Thread sweeper;
        synchronized (session.lock()) {
            sweeper = Thread.ofPlatform().start(() -> manager.sweep(deadline));
            // Long enough for the sweeper to reach the monitor and block on it. Establishing an
            // interleaving, not waiting on a timeout -- the assertions below do not depend on it.
            Thread.sleep(200);
            session.setMaxInactiveInterval(ONE_MINUTE * 60);
        }
        sweeper.join();

        assertFalse(session.isInvalidated(),
            "an extension that landed before the eviction took the lock must be honoured");
        assertEquals(1, manager.size(), "the extended session must still be reachable");
        assertDoesNotThrow(() -> session.getAttribute("probe"));
    }
}

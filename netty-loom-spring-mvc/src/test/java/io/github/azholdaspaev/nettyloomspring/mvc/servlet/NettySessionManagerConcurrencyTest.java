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
    /**
     * For the two same-key attribute races. Their window holds no application code -- a quiet re-bind
     * fires no callback -- so it is a handful of bytecodes wide, and how often it is hit swings with the
     * machine: issue #90 reported single-digit hits per 20,000 rounds where a run here fails inside 200.
     * Budgeted for the slow end, because a race test that stays green on the reporter's hardware is worse
     * than no test at all.
     */
    private static final int ATTRIBUTE_ROUNDS = 20_000;
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

    /**
     * Spins until {@code thread} is blocked entering a monitor, so the interleaving under test is
     * established rather than hoped for. Sleeping instead would make the assertions vacuous whenever the
     * sleep lost -- and vacuous means green, which is the failure mode worth engineering away.
     */
    private static void awaitBlockedOnTheSessionLock(Thread thread) {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.BLOCKED) {
            assertTrue(System.nanoTime() < limit, "the thread never blocked on the session lock");
            Thread.onSpinWait();
        }
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

    /**
     * Counts its own notifications, which is what every attribute race here asserts on: a value must be
     * released exactly once per bind, whatever interleaving produced the binds.
     */
    private static final class CountingValue implements HttpSessionBindingListener {

        private final AtomicInteger bound = new AtomicInteger();
        private final AtomicInteger unbound = new AtomicInteger();

        @Override
        public void valueBound(HttpSessionBindingEvent event) {
            bound.incrementAndGet();
        }

        @Override
        public void valueUnbound(HttpSessionBindingEvent event) {
            unbound.incrementAndGet();
        }

        private String counts() {
            return bound.get() + " bound / " + unbound.get() + " unbound";
        }
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
        // land during the put, leaving a value in a session nothing will ever tear down. Pairing rather
        // than a ceiling on the releases: counting only those leaves an unpaired *bind* invisible, and
        // it is the pairing that holds the remove(key, value) claim honest against a double release.
        for (int round = 0; round < ROUNDS; round++) {
            NettyHttpSession session = manager.create();
            var value = new CountingValue();

            race(() -> session.setAttribute("k", value), session::invalidate);

            assertTrue(session.isInvalidated(), "round " + round + ": invalidate must win eventually");
            assertFalse(session.hasBoundAttributes(),
                "round " + round + ": teardown must leave no attribute bound");
            assertEquals(value.bound.get(), value.unbound.get(),
                "round " + round + ": every bind must be released exactly once, got " + value.counts());
        }
    }

    @Test
    void reBindingAnInstanceWhileTheSessionIsTornDownDoesNotUnbindItTwice() throws InterruptedException {
        // setAttribute skips valueBound when the identical instance is already bound, and separately
        // re-checks invalidation after its put. Combined naively those give one bind and *two* unbinds:
        // the teardown claims the value and fires once, the losing put resurrects it into the map, and
        // the re-check claims it again. A listener that acquires in bound and releases in unbound
        // double-releases; a @SessionScope bean runs @PreDestroy twice.
        for (int round = 0; round < ROUNDS; round++) {
            NettyHttpSession session = manager.create();
            var value = new CountingValue();
            // Already bound, so the re-bind below is the no-valueBound path -- what
            // DefaultSessionAttributeStore.storeAttribute does on every request for @SessionAttributes.
            session.setAttribute("k", value);

            race(() -> {
                try {
                    session.setAttribute("k", value);
                } catch (IllegalStateException expected) {
                    // The invalidation won; the point is how many notifications it produced.
                }
            }, session::invalidate);

            assertEquals(1, value.bound.get(),
                "round " + round + ": a quiet re-bind must stay quiet, got " + value.counts());
            assertEquals(value.bound.get(), value.unbound.get(),
                "round " + round + ": every bind must be released exactly once, got " + value.counts());
        }
    }

    @Test
    void aReBindRacingARemovalReleasesTheValueOncePerBind() throws InterruptedException {
        // The @SessionAttributes write-through path with nothing exotic around it: one request re-stores
        // an instance that is already bound while another removes the key. Deciding "is it already
        // bound?" from a read taken before the publish lets the removal land in between -- it unbinds,
        // the re-bind then resurrects the value with no valueBound of its own, and the teardown releases
        // it a second time. A @SessionScope bean would run its @PreDestroy twice.
        for (int round = 0; round < ATTRIBUTE_ROUNDS; round++) {
            NettyHttpSession session = manager.create();
            var value = new CountingValue();
            session.setAttribute("k", value);

            race(() -> session.setAttribute("k", value), () -> session.removeAttribute("k"));

            // Drains whatever the race left bound, so the counts can be compared at rest.
            session.invalidate();
            assertEquals(value.bound.get(), value.unbound.get(),
                "round " + round + ": every bind must be released exactly once, got " + value.counts());
        }
    }

    @Test
    void aReBindRacingAnotherValueReleasesTheValueOncePerBind() throws InterruptedException {
        // The same shape with a replacement rather than a removal: the racing request's own publish
        // claims the bound value and unbinds it, and the re-bind puts it back unannounced.
        for (int round = 0; round < ATTRIBUTE_ROUNDS; round++) {
            NettyHttpSession session = manager.create();
            var value = new CountingValue();
            session.setAttribute("k", value);

            race(() -> session.setAttribute("k", value), () -> session.setAttribute("k", "replacement"));

            session.invalidate();
            assertEquals(value.bound.get(), value.unbound.get(),
                "round " + round + ": every bind must be released exactly once, got " + value.counts());
        }
    }

    @Test
    void sweepingConcurrentlyWithFindLeavesTheStoreAndTheSessionAgreeing() throws InterruptedException {
        // Named for what it checks, which is weaker than the race it runs: every assertion here is made
        // after both threads have joined, so this pins the *outcome* invariant -- nothing invalidated
        // stays reachable, nothing reachable is invalidated -- and not the locking that produces it.
        // find()'s own guards are covered deterministically by the two tests below.
        for (int round = 0; round < ROUNDS; round++) {
            NettyHttpSession session = manager.create();
            String id = session.getId();
            long deadline = clock.get() + ONE_MINUTE * 1000L;

            race(() -> manager.find(id),
                () -> {
                    clock.set(deadline);
                    manager.sweep(deadline);
                });

            assertEquals(0, manager.size(), "round " + round + ": the expired session must be gone");
            assertNull(manager.find(id), "round " + round + ": an evicted session must not resolve");
            assertTrue(session.isInvalidated(),
                "round " + round + ": a session removed from the store must have been marked invalid");
            clock.set(deadline);
        }
    }

    @Test
    void findRefusesAnInvalidatedSessionStillPresentInTheStore() {
        // find()'s in-lock isInvalidated() guard, without a race: the state it exists for -- marked but
        // not yet unbound from the store -- is exactly what the teardown paths pass through, and can be
        // constructed directly. Racing for it only ever covered it by luck.
        NettyHttpSession session = manager.create();
        session.markInvalidated();

        assertEquals(1, manager.size(), "the session must still be in the store for the guard to matter");
        assertNull(manager.find(session.getId()),
            "find() must not hand back a session that has already been marked invalid");
    }

    @Test
    void findWaitsForAnEvictionThatHasAlreadyTakenTheSessionLock() throws InterruptedException {
        // That find() takes the *session's* lock rather than merely reading flags. Holding the production
        // monitor from the test thread is what makes this deterministic; asserting the finder actually
        // reaches BLOCKED is what stops it going quietly vacuous if find() ever stops locking.
        NettyHttpSession session = manager.create();
        String id = session.getId();

        Thread finder;
        var resolved = new NettyHttpSession[1];
        synchronized (session.lock()) {
            finder = Thread.ofPlatform().start(() -> resolved[0] = manager.find(id));
            awaitBlockedOnTheSessionLock(finder);
            session.markInvalidated();
        }
        finder.join();

        assertNull(resolved[0], "find() must observe the invalidation it was made to wait for");
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
            awaitBlockedOnTheSessionLock(sweeper);
            session.setMaxInactiveInterval(ONE_MINUTE * 60);
        }
        sweeper.join();

        assertFalse(session.isInvalidated(),
            "an extension that landed before the eviction took the lock must be honoured");
        assertEquals(1, manager.size(), "the extended session must still be reachable");
        assertDoesNotThrow(() -> session.getAttribute("probe"));
    }
}

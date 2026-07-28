package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void findNeverHandsBackASessionTheSweeperHasExpired() throws InterruptedException {
        // The sweeper can evict and expire between find()'s map read and its return.
        for (int round = 0; round < ROUNDS; round++) {
            NettyHttpSession session = manager.create();
            String id = session.getId();
            clock.set(ONE_MINUTE * 1000L * (round + 1));

            var resolved = new NettyHttpSession[1];
            race(() -> resolved[0] = manager.find(id), () -> manager.sweep(clock.get()));

            assertTrue(resolved[0] == null || !resolved[0].isInvalidated(),
                "round " + round + ": find() returned a session that had already been invalidated");
            assertEquals(0, manager.size(), "round " + round + ": the expired session must be gone");
        }
    }
}

package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Servlet-contract behaviour of a single session (issue #13).
 *
 * <p>{@link HttpSessionBindingListener} is covered here even though the container-registered listeners
 * ({@code HttpSessionListener} and friends) wait on issue #17: binding callbacks need no
 * {@code addListener} support, because the attribute value itself is the listener. Spring stores a
 * {@code DestructionCallbackBindingListener} as a session attribute, so without {@code valueUnbound}
 * no {@code @SessionScope} bean would ever run its destruction callback.
 */
class NettyHttpSessionTest {

    private static final int ONE_MINUTE = 60;

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
        manager.close();
    }

    /** Records the binding callbacks fired at it, in order, as "bound:name" / "unbound:name". */
    private static final class RecordingValue implements HttpSessionBindingListener {

        private final List<String> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void valueBound(HttpSessionBindingEvent event) {
            events.add("bound:" + event.getName());
        }

        @Override
        public void valueUnbound(HttpSessionBindingEvent event) {
            events.add("unbound:" + event.getName());
        }

        List<String> events() {
            return List.copyOf(events);
        }
    }

    // --- Attributes ---

    @Test
    void attributeRoundTrips() {
        NettyHttpSession session = manager.create();

        session.setAttribute("user", "alice");

        assertEquals("alice", session.getAttribute("user"));
        assertEquals(List.of("user"), Collections.list(session.getAttributeNames()));
    }

    @Test
    void settingAnAttributeToNullRemovesIt() {
        NettyHttpSession session = manager.create();
        session.setAttribute("user", "alice");

        session.setAttribute("user", null);

        assertNull(session.getAttribute("user"),
            "setAttribute(name, null) removes, matching NettyHttpServletRequest.setAttribute");
        assertFalse(session.getAttributeNames().hasMoreElements());
    }

    @Test
    void getAttributeReturnsNullForAnUnknownName() {
        assertNull(manager.create().getAttribute("absent"));
    }

    // --- Binding listeners ---

    @Test
    void bindingListenerIsNotifiedWhenBound() {
        NettyHttpSession session = manager.create();
        RecordingValue value = new RecordingValue();

        session.setAttribute("callback", value);

        assertEquals(List.of("bound:callback"), value.events());
    }

    @Test
    void bindingListenerIsNotifiedWhenRemoved() {
        NettyHttpSession session = manager.create();
        RecordingValue value = new RecordingValue();
        session.setAttribute("callback", value);

        session.removeAttribute("callback");

        assertEquals(List.of("bound:callback", "unbound:callback"), value.events());
    }

    @Test
    void replacingAnAttributeUnbindsThePreviousValue() {
        NettyHttpSession session = manager.create();
        RecordingValue replaced = new RecordingValue();
        RecordingValue replacement = new RecordingValue();
        session.setAttribute("callback", replaced);

        session.setAttribute("callback", replacement);

        assertEquals(List.of("bound:callback", "unbound:callback"), replaced.events());
        assertEquals(List.of("bound:callback"), replacement.events());
    }

    @Test
    void rebindingTheSameInstanceDoesNotUnbindIt() {
        NettyHttpSession session = manager.create();
        RecordingValue value = new RecordingValue();
        session.setAttribute("callback", value);

        session.setAttribute("callback", value);

        assertEquals(List.of("bound:callback", "bound:callback"), value.events(),
            "Re-binding the identical instance must not fire valueUnbound: the value is still bound");
    }

    @Test
    void invalidateUnbindsEveryAttribute() {
        NettyHttpSession session = manager.create();
        RecordingValue value = new RecordingValue();
        session.setAttribute("callback", value);

        session.invalidate();

        assertEquals(List.of("bound:callback", "unbound:callback"), value.events());
    }

    @Test
    void expiryUnbindsEveryAttribute() {
        NettyHttpSession session = manager.create();
        RecordingValue value = new RecordingValue();
        session.setAttribute("callback", value);

        clock.set(ONE_MINUTE * 1000L);
        assertEquals(1, manager.sweep(clock.get()));

        assertEquals(List.of("bound:callback", "unbound:callback"), value.events(),
            "A swept session must unbind its values, or @SessionScope beans leak on timeout");
    }

    @Test
    void aThrowingBindingListenerDoesNotAbortTheUnbindingOfOtherAttributes() {
        NettyHttpSession session = manager.create();
        RecordingValue survivor = new RecordingValue();
        session.setAttribute("bad", new HttpSessionBindingListener() {
            @Override
            public void valueUnbound(HttpSessionBindingEvent event) {
                throw new IllegalStateException("listener blew up");
            }
        });
        session.setAttribute("good", survivor);

        assertDoesNotThrow(session::invalidate);

        assertEquals(List.of("bound:good", "unbound:good"), survivor.events(),
            "One misbehaving listener must not stop the sweeper or strand the remaining values");
    }

    // --- Invalidation ---

    @Test
    void aSecondInvalidateThrows() {
        NettyHttpSession session = manager.create();
        session.invalidate();

        assertThrows(IllegalStateException.class, session::invalidate);
    }

    @Test
    void attributeAccessAfterInvalidateThrows() {
        NettyHttpSession session = manager.create();
        session.invalidate();

        assertThrows(IllegalStateException.class, () -> session.getAttribute("user"));
        assertThrows(IllegalStateException.class, () -> session.setAttribute("user", "alice"));
        assertThrows(IllegalStateException.class, () -> session.removeAttribute("user"));
        assertThrows(IllegalStateException.class, session::getAttributeNames);
    }

    @Test
    void timeAndFreshnessAccessorsThrowAfterInvalidate() {
        NettyHttpSession session = manager.create();
        session.invalidate();

        assertThrows(IllegalStateException.class, session::getCreationTime);
        assertThrows(IllegalStateException.class, session::getLastAccessedTime);
        assertThrows(IllegalStateException.class, session::isNew);
    }

    @Test
    void getIdStillWorksAfterInvalidate() {
        NettyHttpSession session = manager.create();
        String id = session.getId();

        session.invalidate();

        // Servlet 6.0 dropped the IllegalStateException here, so logging and audit code can still name
        // the session it just destroyed.
        assertEquals(id, assertDoesNotThrow(session::getId));
    }

    @Test
    void intervalAndContextAccessorsStillWorkAfterInvalidate() {
        NettyHttpSession session = manager.create();
        session.invalidate();

        assertDoesNotThrow(session::getMaxInactiveInterval);
        assertDoesNotThrow(() -> session.setMaxInactiveInterval(5));
        assertDoesNotThrow(session::getServletContext);
    }

    @Test
    void anExpiredSessionBehavesAsInvalidated() {
        NettyHttpSession session = manager.create();
        clock.set(ONE_MINUTE * 1000L);
        manager.sweep(clock.get());

        assertThrows(IllegalStateException.class, () -> session.getAttribute("user"),
            "A request that resolved this session a moment before the sweep must fail loudly rather "
                + "than write into a map nothing reads");
    }

    // --- Times, freshness and interval ---

    @Test
    void getLastAccessedTimeReportsThePreviousRequestNotTheCurrentOne() {
        NettyHttpSession session = manager.create();

        clock.set(5_000L);
        manager.find(session.getId());
        assertEquals(0L, session.getLastAccessedTime(),
            "During the second request, the last access is the first request's");

        clock.set(9_000L);
        manager.find(session.getId());
        assertEquals(5_000L, session.getLastAccessedTime());
    }

    @Test
    void getCreationTimeIsFixedAtCreation() {
        NettyHttpSession session = manager.create();

        clock.set(5_000L);
        manager.find(session.getId());

        assertEquals(0L, session.getCreationTime());
    }

    @Test
    void isNewStaysTrueForTheWholeRequestThatCreatedTheSession() {
        NettyHttpSession session = manager.create();

        clock.set(5_000L);

        assertTrue(session.isNew(), "Nothing has presented the id back yet");
    }

    @Test
    void setMaxInactiveIntervalOverridesTheManagerDefault() {
        NettyHttpSession session = manager.create();

        session.setMaxInactiveInterval(ONE_MINUTE * 10);
        clock.set(ONE_MINUTE * 1000L * 5);

        assertSame(session, manager.find(session.getId()),
            "The per-session interval, not the manager default, decides expiry");
    }

    @Test
    void setMaxInactiveIntervalToZeroDisablesExpiryForThatSessionOnly() {
        NettyHttpSession immortal = manager.create();
        NettyHttpSession mortal = manager.create();

        immortal.setMaxInactiveInterval(0);
        clock.set(ONE_MINUTE * 1000L);

        assertSame(immortal, manager.find(immortal.getId()));
        assertNull(manager.find(mortal.getId()));
    }

    @Test
    void getServletContextReturnsTheOwningContext() {
        assertSame(servletContext, manager.create().getServletContext());
    }
}

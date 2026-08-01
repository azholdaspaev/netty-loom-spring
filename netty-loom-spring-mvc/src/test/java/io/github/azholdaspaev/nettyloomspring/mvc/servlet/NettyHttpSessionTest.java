package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

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
 * <p>{@link HttpSessionBindingListener} is a different mechanism from the container-registered listeners
 * ({@code HttpSessionListener} and friends, issue #17): binding callbacks need no {@code addListener}
 * support, because the attribute value itself is the listener. Spring stores a
 * {@code DestructionCallbackBindingListener} as a session attribute, so without {@code valueUnbound}
 * no {@code @SessionScope} bean would ever run its destruction callback. Both are covered here, and the
 * order between them is part of the contract: a value's own {@code valueUnbound} runs before the
 * container listeners' {@code attributeRemoved}.
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
    void rebindingTheSameInstanceNotifiesNeitherSide() {
        NettyHttpSession session = manager.create();
        RecordingValue value = new RecordingValue();
        session.setAttribute("callback", value);

        session.setAttribute("callback", value);

        // Neither bound nor unbound: the value was already bound under this name and still is. Firing
        // valueBound again while valueUnbound stays guarded would make an acquire-in-bound /
        // release-in-unbound listener acquire twice and release once. Tomcat guards both sides too.
        assertEquals(List.of("bound:callback"), value.events());
    }

    @Test
    void bindingEventsCarryTheValue() {
        // The canonical HttpSessionBindingListener is a resource holder that reads event.getValue() to
        // know what to release; the two-argument HttpSessionBindingEvent leaves it null.
        NettyHttpSession session = manager.create();
        var seen = new java.util.ArrayList<Object>();
        var value = new HttpSessionBindingListener() {
            @Override
            public void valueBound(HttpSessionBindingEvent event) {
                seen.add(event.getValue());
            }

            @Override
            public void valueUnbound(HttpSessionBindingEvent event) {
                seen.add(event.getValue());
            }
        };

        session.setAttribute("callback", value);
        session.removeAttribute("callback");

        assertEquals(List.of(value, value), seen, "both callbacks must receive the bound value");
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

    // --- Container-registered session listeners (issue #17) ---

    @Test
    void sessionDestroyedFiresOnceOnInvalidate() {
        var destroyed = new ArrayList<String>();
        servletContext.addListener(new HttpSessionListener() {
            @Override
            public void sessionDestroyed(HttpSessionEvent event) {
                destroyed.add(event.getSession().getId());
            }
        });
        NettyHttpSession session = manager.create();
        String id = session.getId();

        session.invalidate();

        assertEquals(List.of(id), destroyed);
    }

    @Test
    void attributesAreStillReadableFromInsideSessionDestroyed() {
        // The window Tomcat's StandardSession.expiring opens. Spring Security's HttpSessionDestroyedEvent
        // walks getAttributeNames() to collect the SecurityContexts it is publishing the logout for, so a
        // session that has already slammed shut makes the listener useless.
        var seen = new ArrayList<String>();
        servletContext.addListener(new HttpSessionListener() {
            @Override
            public void sessionDestroyed(HttpSessionEvent event) {
                HttpSession session = event.getSession();
                session.getAttributeNames().asIterator()
                    .forEachRemaining(name -> seen.add(name + "=" + session.getAttribute(name)));
            }
        });
        NettyHttpSession session = manager.create();
        session.setAttribute("user", "alice");

        session.invalidate();

        assertEquals(List.of("user=alice"), seen);
    }

    @Test
    void theDestroyWindowClosesOnceTeardownIsOver() {
        // Strictly scoped to the notification: afterwards the session is as invalid as it has always been.
        NettyHttpSession session = manager.create();
        session.setAttribute("user", "alice");

        session.invalidate();

        assertThrows(IllegalStateException.class, () -> session.getAttribute("user"));
    }

    @Test
    void attributeMutationsFireTheContainerAttributeListener() {
        var events = new ArrayList<String>();
        servletContext.addListener(new HttpSessionAttributeListener() {
            @Override
            public void attributeAdded(HttpSessionBindingEvent event) {
                events.add("added:" + event.getName() + "=" + event.getValue());
            }

            @Override
            public void attributeReplaced(HttpSessionBindingEvent event) {
                events.add("replaced:" + event.getName() + "=" + event.getValue());
            }

            @Override
            public void attributeRemoved(HttpSessionBindingEvent event) {
                events.add("removed:" + event.getName() + "=" + event.getValue());
            }
        });
        NettyHttpSession session = manager.create();

        session.setAttribute("user", "alice");
        session.setAttribute("user", "bob");
        session.removeAttribute("user");

        // Replacement reports the displaced value, matching the ServletContext side.
        assertEquals(List.of("added:user=alice", "replaced:user=alice", "removed:user=bob"), events);
    }

    @Test
    void tearingDownASessionRemovesItsAttributesThroughTheListener() {
        // Tomcat's expire() removes each attribute with notification on, so an audit listener sees the
        // same "attribute gone" event whether the application removed it or the container did.
        var removed = new ArrayList<String>();
        servletContext.addListener(new HttpSessionAttributeListener() {
            @Override
            public void attributeRemoved(HttpSessionBindingEvent event) {
                removed.add(event.getName() + "=" + event.getValue());
            }
        });
        NettyHttpSession session = manager.create();
        session.setAttribute("user", "alice");

        session.invalidate();

        assertEquals(List.of("user=alice"), removed);
    }

    @Test
    void anAttributeClaimedBackByInvalidationWasAnnouncedFirst() {
        // The claim-back branch: compute publishes while the session is still valid, the session is
        // invalidated a moment later, and setAttribute takes the value back -- notifying attributeRemoved.
        // The add or replace it pairs with must already have been announced, or a listener maintaining an
        // index (get(name).remove(value)) is told to remove something it was never told about.
        //
        // Deterministic rather than threaded: the displaced value's own valueUnbound is application code
        // that setAttribute runs after compute has published, which is exactly the window a concurrent
        // invalidate() occupies.
        var events = new ArrayList<String>();
        servletContext.addListener(new HttpSessionAttributeListener() {
            @Override
            public void attributeAdded(HttpSessionBindingEvent event) {
                events.add("added:" + event.getName());
            }

            @Override
            public void attributeReplaced(HttpSessionBindingEvent event) {
                events.add("replaced:" + event.getName());
            }

            @Override
            public void attributeRemoved(HttpSessionBindingEvent event) {
                events.add("removed:" + event.getName());
            }
        });
        NettyHttpSession session = manager.create();
        session.setAttribute("cart", new HttpSessionBindingListener() {
            @Override
            public void valueUnbound(HttpSessionBindingEvent event) {
                session.invalidate();
            }
        });

        assertThrows(IllegalStateException.class, () -> session.setAttribute("cart", "second"));

        assertEquals(List.of("added:cart", "replaced:cart", "removed:cart"), events,
            "every attributeRemoved must follow the add or replace that announced the value it names");
    }

    @Test
    void aRemovalUnbindsTheValueBeforeAnnouncingItGone() {
        // notifyRemoved's javadoc calls this ordering Tomcat's, and this class's own javadoc calls the
        // order part of the contract -- so it needs a test, or a refactor can reverse it silently. It
        // matters for the sibling of the case setAttribute's early announcement fixed: a valueUnbound
        // that reads the session would otherwise run after the container was told the value was gone.
        var events = new ArrayList<String>();
        servletContext.addListener(new HttpSessionAttributeListener() {
            @Override
            public void attributeRemoved(HttpSessionBindingEvent event) {
                events.add("attributeRemoved:" + event.getName());
            }
        });
        NettyHttpSession session = manager.create();
        session.setAttribute("cart", new HttpSessionBindingListener() {
            @Override
            public void valueUnbound(HttpSessionBindingEvent event) {
                events.add("valueUnbound:" + event.getName());
            }
        });

        session.removeAttribute("cart");

        assertEquals(List.of("valueUnbound:cart", "attributeRemoved:cart"), events,
            "the value releases itself before the container listeners hear it is gone");
    }

    @Test
    void removingAnAbsentSessionAttributeNotifiesNothing() {
        // The identical rule is already asserted on both sibling paths --
        // shouldNotFireContextAttributeRemovedForAnAbsentName and
        // removingAnAbsentRequestAttributeNotifiesNothing -- so this was an omission, not a decision.
        var events = new ArrayList<String>();
        servletContext.addListener(new HttpSessionAttributeListener() {
            @Override
            public void attributeRemoved(HttpSessionBindingEvent event) {
                events.add(event.getName() + "=" + event.getValue());
            }
        });
        NettyHttpSession session = manager.create();

        session.removeAttribute("never-set");

        assertTrue(events.isEmpty(), "a removal that changes nothing notifies nothing; got " + events);
    }

    @Test
    void aThrowingSessionDestroyedListenerDoesNotAbortTheTeardown() {
        // fireSessionDestroyed runs at the top of unbindAll, before the attribute unbind loop. If it
        // propagated, one bad listener would abort the rest of teardown -- no attribute unbound, so every
        // @SessionScope destruction callback and every valueUnbound skipped -- and the failure would
        // escape into invalidate(), the sweep and the shutdown drain.
        var unbound = new ArrayList<String>();
        servletContext.addListener(new HttpSessionListener() {
            @Override
            public void sessionDestroyed(HttpSessionEvent event) {
                throw new IllegalStateException("session registry is down");
            }
        });
        NettyHttpSession session = manager.create();
        session.setAttribute("cart", new HttpSessionBindingListener() {
            @Override
            public void valueUnbound(HttpSessionBindingEvent event) {
                unbound.add(event.getName());
            }
        });

        assertDoesNotThrow(session::invalidate);

        assertEquals(List.of("cart"), unbound, "teardown must finish unbinding despite the failure");
        assertFalse(session.hasBoundAttributes());
    }

    @Test
    void setAttributeToNullFiresRemovedRatherThanAdded() {
        var events = new ArrayList<String>();
        servletContext.addListener(new HttpSessionAttributeListener() {
            @Override
            public void attributeAdded(HttpSessionBindingEvent event) {
                events.add("added");
            }

            @Override
            public void attributeRemoved(HttpSessionBindingEvent event) {
                events.add("removed:" + event.getValue());
            }
        });
        NettyHttpSession session = manager.create();
        session.setAttribute("user", "alice");

        session.setAttribute("user", null);

        assertEquals(List.of("added", "removed:alice"), events);
    }
}

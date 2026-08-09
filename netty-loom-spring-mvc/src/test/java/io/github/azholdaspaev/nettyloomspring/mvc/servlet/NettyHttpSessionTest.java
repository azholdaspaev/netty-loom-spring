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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Servlet-contract behaviour of a single session (issue #13). {@link HttpSessionBindingListener} is a
 * different mechanism from the container-registered listeners (issue #17): binding callbacks need no
 * {@code addListener} support, because the attribute value itself is the listener. Spring stores a
 * {@code DestructionCallbackBindingListener} as a session attribute, so without {@code valueUnbound} no
 * {@code @SessionScope} bean would ever run its destruction callback.
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

    /**
     * Records the binding callbacks fired at it, in order, as "bound:name" / "unbound:name".
     */
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

        // Firing valueBound again while valueUnbound stays guarded would make an acquire-in-bound /
        // release-in-unbound listener acquire twice and release once. Tomcat guards both sides too.
        assertEquals(List.of("bound:callback"), value.events());
    }

    @Test
    void bindingAnInstanceTwiceBeforeEitherPublishesReleasesItOncePerBind() {
        // Two requests binding the same instance can both observe it absent before either publishes, so
        // both fire valueBound while only one of them is a real transition -- the second finds the value
        // already there and stays quiet, leaving a bind nothing releases.
        //
        // Deterministic rather than threaded: valueBound is application code that runs inside exactly
        // that window, so re-entering setAttribute from it occupies the window the second request would.
        NettyHttpSession session = manager.create();
        var events = new ArrayList<String>();
        var reentered = new boolean[1];
        var value = new HttpSessionBindingListener() {
            @Override
            public void valueBound(HttpSessionBindingEvent event) {
                events.add("bound");
                if (!reentered[0]) {
                    reentered[0] = true;
                    session.setAttribute("cart", event.getValue());
                }
            }

            @Override
            public void valueUnbound(HttpSessionBindingEvent event) {
                events.add("unbound");
            }
        };

        session.setAttribute("cart", value);
        session.invalidate();

        assertEquals(List.of("bound", "unbound"), events,
            "a value bound once must be released once, whatever raced the bind");
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

    @Test
    void aLinkageErrorFromValueUnboundDoesNotAbortTheUnbindingOfOtherAttributes() {
        // The other shape the same listener fails in, and the one a value touching a lazily-loaded
        // release helper actually raises. It has to be swallowed for the same reason as the exception
        // above: the values ordered after it in the teardown are otherwise left bound, with the
        // @PreDestroy of every @SessionScope bean among them unrun.
        NettyHttpSession session = manager.create();
        RecordingValue survivor = new RecordingValue();
        session.setAttribute("bad", new HttpSessionBindingListener() {
            @Override
            public void valueUnbound(HttpSessionBindingEvent event) {
                throw new NoClassDefFoundError("com/example/Missing");
            }
        });
        session.setAttribute("good", survivor);

        assertDoesNotThrow(session::invalidate);

        assertEquals(List.of("bound:good", "unbound:good"), survivor.events(),
            "One misbehaving listener must not stop the sweeper or strand the remaining values");
    }

    @Test
    void aVirtualMachineErrorFromValueUnboundIsNotSwallowed() {
        NettyHttpSession session = manager.create();
        session.setAttribute("bad", new HttpSessionBindingListener() {
            @Override
            public void valueUnbound(HttpSessionBindingEvent event) {
                throw new OutOfMemoryError("Java heap space");
            }
        });

        assertThrows(OutOfMemoryError.class, session::invalidate);
    }

    @Test
    void aThrowingValueBoundStillReleasesTheValueItDisplaced() {
        // valueBound runs after the publish -- it has to, since only the publish knows what it displaced
        // -- so by the time it fails the previous value is already out of the map and nothing else can
        // reach it. Its release is owed regardless, or a failed bind strands whatever it was holding.
        NettyHttpSession session = manager.create();
        RecordingValue displaced = new RecordingValue();
        session.setAttribute("cart", displaced);
        var failing = new HttpSessionBindingListener() {
            @Override
            public void valueBound(HttpSessionBindingEvent event) {
                throw new IllegalStateException("listener blew up");
            }
        };

        assertThrows(IllegalStateException.class, () -> session.setAttribute("cart", failing));

        assertEquals(List.of("bound:cart", "unbound:cart"), displaced.events());
        assertSame(failing, session.getAttribute("cart"),
            "the value is published before valueBound runs, so its failure cannot veto the binding the "
                + "way Tomcat's does");
    }

    @Test
    void anErrorFromTheDisplacedValueDoesNotReplaceTheFailedBind() {
        // The bind failure is the one the caller needs to see -- propagating it is the whole reason
        // valueBound is not routed through the quiet path. The release it owes runs in a finally, and a
        // finally completing abruptly discards whatever the try was throwing (JLS 14.20.2), with nothing
        // attached as suppressed: addSuppressed is try-with-resources only.
        NettyHttpSession session = manager.create();
        session.setAttribute("cart", new HttpSessionBindingListener() {
            @Override
            public void valueUnbound(HttpSessionBindingEvent event) {
                throw new NoClassDefFoundError("com/example/Missing");
            }
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> session.setAttribute("cart", new HttpSessionBindingListener() {
                @Override
                public void valueBound(HttpSessionBindingEvent event) {
                    throw new IllegalStateException("inventory locked");
                }
            }));

        assertEquals("inventory locked", thrown.getMessage(),
            "the release of the displaced value must not mask the bind that failed");
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
    void aValueInvalidatingTheSessionFromValueBoundWasAnnouncedFirst() {
        // The sibling of the case above, for the bound side: valueBound runs after compute has published,
        // so it too can invalidate and provoke the claim-back. Its add must already be announced, or the
        // index listener is again told to remove something it was never told about.
        var events = new ArrayList<String>();
        servletContext.addListener(new HttpSessionAttributeListener() {
            @Override
            public void attributeAdded(HttpSessionBindingEvent event) {
                events.add("added:" + event.getName());
            }

            @Override
            public void attributeRemoved(HttpSessionBindingEvent event) {
                events.add("removed:" + event.getName());
            }
        });
        NettyHttpSession session = manager.create();

        assertThrows(IllegalStateException.class, () -> session.setAttribute("cart",
            new HttpSessionBindingListener() {
                @Override
                public void valueBound(HttpSessionBindingEvent event) {
                    session.invalidate();
                }
            }));

        assertEquals(List.of("added:cart", "removed:cart"), events,
            "every attributeRemoved must follow the add or replace that announced the value it names");
    }

    @Test
    void aRemovalUnbindsTheValueBeforeAnnouncingItGone() {
        // A valueUnbound that reads the session must run before the container is told the value is gone.
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
    void aThrowingAttributeRemovedListenerDoesNotAbortTheTeardown() {
        // fireSessionAttributeRemoved runs inside the unbind loop, so a propagating attributeRemoved would
        // abort the remaining unbinds and escape into invalidate(), the sweep and the shutdown drain.
        var unbound = new ArrayList<String>();
        servletContext.addListener(new HttpSessionAttributeListener() {
            @Override
            public void attributeRemoved(HttpSessionBindingEvent event) {
                throw new IllegalStateException("audit index is down");
            }
        });
        NettyHttpSession session = manager.create();
        for (String name : List.of("first", "second", "third")) {
            session.setAttribute(name, new HttpSessionBindingListener() {
                @Override
                public void valueUnbound(HttpSessionBindingEvent event) {
                    unbound.add(event.getName());
                }
            });
        }

        assertDoesNotThrow(session::invalidate);

        assertEquals(Set.of("first", "second", "third"), new HashSet<>(unbound),
            "every attribute must still be unbound; got " + unbound);
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

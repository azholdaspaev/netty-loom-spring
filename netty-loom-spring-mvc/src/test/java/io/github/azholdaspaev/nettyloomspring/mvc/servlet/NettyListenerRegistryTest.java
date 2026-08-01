package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeEvent;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registration and dispatch of the container-registered servlet listeners (issue #17).
 *
 * <p>Covers the registry in isolation: which types are accepted, what order each event reaches them in,
 * and which failures are allowed to escape. The wiring that decides <em>when</em> each event fires lives
 * with the class that owns the state -- {@link DefaultNettyServletContextTest},
 * {@link NettySessionManagerTest}, {@link NettyHttpSessionTest} and the dispatcher's own test.
 */
class NettyListenerRegistryTest {

    private DefaultNettyServletContext servletContext;
    private NettyListenerRegistry registry;
    private List<String> events;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        servletContext = new DefaultNettyServletContext();
        registry = new NettyListenerRegistry(servletContext);
        events = new ArrayList<>();
        // One session for the whole class: the registry only needs a non-null event source, and every
        // create() lazily starts the manager's sweeper thread.
        session = servletContext.getSessionManager().create();
    }

    @AfterEach
    void tearDown() {
        servletContext.close();
    }

    /**
     * Implements every supported interface at once, recording "event:label". One class rather than
     * seven keeps the ordering assertions below readable, and doubles as the multi-interface case:
     * a single instance must land in every bucket it qualifies for.
     */
    private final class RecordingListener implements ServletContextListener, ServletContextAttributeListener,
        ServletRequestListener, ServletRequestAttributeListener, HttpSessionListener,
        HttpSessionAttributeListener, HttpSessionIdListener {

        private final String label;

        RecordingListener(String label) {
            this.label = label;
        }

        private void record(String event) {
            events.add(event + ":" + label);
        }

        @Override
        public void contextInitialized(ServletContextEvent event) {
            record("contextInitialized");
        }

        @Override
        public void contextDestroyed(ServletContextEvent event) {
            record("contextDestroyed");
        }

        @Override
        public void attributeAdded(ServletContextAttributeEvent event) {
            record("contextAttributeAdded");
        }

        @Override
        public void attributeReplaced(ServletContextAttributeEvent event) {
            record("contextAttributeReplaced");
        }

        @Override
        public void attributeRemoved(ServletContextAttributeEvent event) {
            record("contextAttributeRemoved");
        }

        @Override
        public void requestInitialized(ServletRequestEvent event) {
            record("requestInitialized");
        }

        @Override
        public void requestDestroyed(ServletRequestEvent event) {
            record("requestDestroyed");
        }

        @Override
        public void attributeAdded(ServletRequestAttributeEvent event) {
            record("requestAttributeAdded");
        }

        @Override
        public void attributeReplaced(ServletRequestAttributeEvent event) {
            record("requestAttributeReplaced");
        }

        @Override
        public void attributeRemoved(ServletRequestAttributeEvent event) {
            record("requestAttributeRemoved");
        }

        @Override
        public void sessionCreated(HttpSessionEvent event) {
            record("sessionCreated");
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent event) {
            record("sessionDestroyed");
        }

        @Override
        public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
            record("sessionIdChanged:" + oldSessionId);
        }

        @Override
        public void attributeAdded(HttpSessionBindingEvent event) {
            record("sessionAttributeAdded");
        }

        @Override
        public void attributeReplaced(HttpSessionBindingEvent event) {
            record("sessionAttributeReplaced");
        }

        @Override
        public void attributeRemoved(HttpSessionBindingEvent event) {
            record("sessionAttributeRemoved");
        }
    }

    private static ServletRequest request() {
        // The registry only carries the request through to the event; nothing calls into it.
        return null;
    }

    // --- Registration ---

    @Test
    void oneInstanceLandsInEveryBucketItQualifiesFor() {
        registry.addListener(new RecordingListener("all"));

        registry.fireContextInitialized();
        registry.fireContextAttributeAdded("k", "v");
        registry.fireRequestInitialized(request());
        registry.fireRequestAttributeAdded(request(), "k", "v");
        registry.fireSessionCreated(session);
        registry.fireSessionAttributeAdded(session, "k", "v");
        registry.fireSessionIdChanged(session, "old");

        assertEquals(List.of("contextInitialized:all", "contextAttributeAdded:all", "requestInitialized:all",
            "requestAttributeAdded:all", "sessionCreated:all", "sessionAttributeAdded:all",
            "sessionIdChanged:old:all"), events);
    }

    @Test
    void aListenerOfNoSupportedTypeIsRejected() {
        // Legal EventListener, but not one this container fires -- accepting it silently would leave the
        // application believing it is wired up.
        EventListener unsupported = new EventListener() {
        };

        IllegalArgumentException failure =
            assertThrows(IllegalArgumentException.class, () -> registry.addListener(unsupported));

        assertTrue(failure.getMessage().contains(ServletContextListener.class.getName()),
            "the message must name the types that are accepted; got " + failure.getMessage());
    }

    @Test
    void aContextListenerCannotRegisterOnceTheInitPassHasStarted() {
        // The two passes read the list differently: fireContextInitialized iterates a CopyOnWriteArrayList
        // with an enhanced-for, whose iterator is a snapshot taken at loop entry, while fireQuietlyReversed
        // indexes it live. A ServletContextListener added in between would therefore miss
        // contextInitialized and still receive contextDestroyed -- the exact state the init pass exists to
        // prevent. The spec closes this at registration, and Tomcat clears
        // newServletContextListenerAllowed immediately before listenerStart fires.
        registry.fireContextInitialized();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> registry.addListener(new RecordingListener("late")));

        assertTrue(failure.getMessage().contains(ServletContextListener.class.getSimpleName()),
            "the message must name the type that is now refused; got " + failure.getMessage());
    }

    @Test
    void theOtherSixTypesStillRegisterDuringFilterAndServletInit() {
        // markInitialized is deliberately later than the init pass so a Filter.init can still configure
        // the container. Only ServletContextListener closes early, because only it has already fired.
        registry.fireContextInitialized();

        assertDoesNotThrow(() -> registry.addListener((HttpSessionIdListener) (event, oldId) -> {
        }), "a session id listener has nothing it has already missed");
        assertDoesNotThrow(() -> registry.addListener(new ServletRequestListener() {
        }), "no request has been served yet");
    }

    @Test
    void registrationIsRefusedOnceTheContextIsInitialized() {
        registry.markInitialized();

        assertThrows(IllegalStateException.class, () -> registry.addListener(new RecordingListener("late")));
    }

    // --- Ordering ---

    @Test
    void initializationEventsFireInRegistrationOrder() {
        registry.addListener(new RecordingListener("first"));
        registry.addListener(new RecordingListener("second"));

        registry.fireContextInitialized();
        registry.fireRequestInitialized(request());
        registry.fireSessionCreated(session);

        assertEquals(List.of("contextInitialized:first", "contextInitialized:second",
            "requestInitialized:first", "requestInitialized:second",
            "sessionCreated:first", "sessionCreated:second"), events);
    }

    @Test
    void destructionEventsFireInReverseRegistrationOrder() {
        // The servlet spec reverses the destroy notifications so a listener registered later -- and
        // therefore built on top of an earlier one -- tears down before what it depends on.
        registry.addListener(new RecordingListener("first"));
        registry.addListener(new RecordingListener("second"));

        registry.fireContextDestroyed();
        registry.fireRequestDestroyed(request());
        registry.fireSessionDestroyed(session);

        assertEquals(List.of("contextDestroyed:second", "contextDestroyed:first",
            "requestDestroyed:second", "requestDestroyed:first",
            "sessionDestroyed:second", "sessionDestroyed:first"), events);
    }

    // --- Events ---

    @Test
    void attributeEventsCarryTheOwnerAndTheValue() {
        var seen = new Object[3];
        registry.addListener(new ServletContextAttributeListener() {
            @Override
            public void attributeReplaced(ServletContextAttributeEvent event) {
                seen[0] = event.getServletContext();
                seen[1] = event.getName();
                // Replacement reports the *old* value, per the ServletContextAttributeEvent contract.
                seen[2] = event.getValue();
            }
        });

        registry.fireContextAttributeReplaced("user", "alice");

        assertSame(servletContext, seen[0]);
        assertEquals("user", seen[1]);
        assertEquals("alice", seen[2]);
    }

    @Test
    void sessionIdChangedCarriesTheOldId() {
        var seen = new String[1];
        registry.addListener((HttpSessionIdListener) (event, oldSessionId) -> seen[0] = oldSessionId);

        registry.fireSessionIdChanged(session, "the-old-id");

        assertEquals("the-old-id", seen[0]);
    }

    // --- Failure isolation ---

    @Test
    void aFailingListenerDoesNotStrandTheRestOfATeardown() {
        // Teardown has no caller in a position to handle the failure, so it is logged and the remaining
        // listeners still run -- the same rule NettyHttpSession applies to valueUnbound.
        registry.addListener(new RecordingListener("survivor"));
        registry.addListener(new ServletContextListener() {
            @Override
            public void contextDestroyed(ServletContextEvent event) {
                throw new IllegalStateException("boom");
            }
        });

        registry.fireContextDestroyed();

        assertEquals(List.of("contextDestroyed:survivor"), events);
    }

    @Test
    void aFailingListenerAbortsStartup() {
        // The opposite rule: a listener that cannot initialize must not leave the application serving
        // traffic in a half-configured state, so contextInitialized propagates and startup fails.
        registry.addListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                throw new IllegalStateException("boom");
            }
        });

        assertThrows(IllegalStateException.class, () -> registry.fireContextInitialized());
    }

    @Test
    void everyListenerIsInitializedEvenWhenAnEarlierOneFails() {
        // The destroy pass walks the whole list, so the init pass has to as well. Aborting on the first
        // throw would leave listeners registered after it never initialized, and the startup backstop
        // then calls close() -- which fires contextDestroyed at them anyway, tearing down what was never
        // set up. Tomcat's listenerStart() catches per listener and records failure instead of returning.
        registry.addListener(new RecordingListener("first"));
        registry.addListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                throw new IllegalStateException("boom");
            }
        });
        registry.addListener(new RecordingListener("third"));

        assertThrows(IllegalStateException.class, () -> registry.fireContextInitialized(),
            "the failure must still abort startup");

        assertEquals(List.of("contextInitialized:first", "contextInitialized:third"), events);
    }

    @Test
    void anErrorFromOneListenerStillInitializesTheRest() {
        // contextInitialized is where applications touch static initializers and lazily-loaded classes,
        // so ExceptionInInitializerError and NoClassDefFoundError are the realistic failures -- and both
        // are Error, not RuntimeException. An Error escaping the loop reaches exactly the asymmetry this
        // loop exists to close, because the state is already STARTED and close() will still destroy
        // everyone. NettySessionManager.sweepQuietly catches Throwable for the same reason.
        registry.addListener(new RecordingListener("first"));
        registry.addListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                throw new NoClassDefFoundError("com/example/Missing");
            }
        });
        registry.addListener(new RecordingListener("third"));

        assertThrows(NoClassDefFoundError.class, () -> registry.fireContextInitialized(),
            "an Error must still abort startup");

        assertEquals(List.of("contextInitialized:first", "contextInitialized:third"), events);
    }

    @Test
    void anErrorFromATeardownListenerDoesNotStrandTheRest() {
        registry.addListener(new RecordingListener("survivor"));
        registry.addListener(new ServletContextListener() {
            @Override
            public void contextDestroyed(ServletContextEvent event) {
                throw new NoClassDefFoundError("com/example/Missing");
            }
        });

        assertDoesNotThrow(() -> registry.fireContextDestroyed());

        assertEquals(List.of("contextDestroyed:survivor"), events);
    }

    /** Delivers a checked exception where the compiler thinks none can occur, as Kotlin and Lombok do. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @Test
    void aCheckedExceptionFromAListenerKeepsItsDiagnosis() {
        // contextInitialized declares no checked exception, but that binds the Java compiler, not the
        // runtime: a listener written in Kotlin -- which has no checked exceptions -- or one using
        // Lombok's @SneakyThrows delivers an IOException here. The widened catch admits it, so the
        // rethrow needs somewhere to put it; a bare cast to RuntimeException would throw
        // ClassCastException and take the real failure and everything attached to it with it.
        registry.addListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                sneakyThrow(new IOException("listener could not read its config"));
            }
        });
        registry.addListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                throw new IllegalStateException("second failure");
            }
        });

        RuntimeException thrown =
            assertThrows(RuntimeException.class, () -> registry.fireContextInitialized());

        Throwable cause = thrown.getCause();
        assertInstanceOf(IOException.class, cause, "the checked failure must survive as the cause");
        assertEquals("listener could not read its config", cause.getMessage());
        assertEquals(1, cause.getSuppressed().length,
            "the later failure must still be attached rather than destroyed with it");
    }

    @Test
    void theFirstInitializationFailureIsTheOneReported() {
        // Later failures are attached rather than dropped, so a log shows every listener that broke.
        registry.addListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                throw new IllegalStateException("first failure");
            }
        });
        registry.addListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                throw new IllegalStateException("second failure");
            }
        });

        RuntimeException thrown =
            assertThrows(RuntimeException.class, () -> registry.fireContextInitialized());

        assertEquals("first failure", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length, "the later failure must not be dropped");
        assertEquals("second failure", thrown.getSuppressed()[0].getMessage());
    }

    @Test
    void aVirtualMachineErrorIsNotSwallowedByAQuietPass() {
        // catch (Throwable) is right for NoClassDefFoundError and wrong for OutOfMemoryError: logging a
        // VM error at WARN and carrying on leaves the container running on a JVM that has already
        // failed, and log.warn allocates, so the next one escapes anyway. Tomcat pairs every
        // catch (Throwable) with handleThrowable, which rethrows VirtualMachineError first.
        registry.addListener(new RecordingListener("survivor"));
        registry.addListener(new ServletContextListener() {
            @Override
            public void contextDestroyed(ServletContextEvent event) {
                throw new OutOfMemoryError("Java heap space");
            }
        });

        assertThrows(OutOfMemoryError.class, () -> registry.fireContextDestroyed());
    }

    @Test
    void aLinkageErrorIsStillSwallowedByAQuietPass() {
        // The case the widened catch exists for, and the line between the two.
        registry.addListener(new RecordingListener("survivor"));
        registry.addListener(new ServletContextListener() {
            @Override
            public void contextDestroyed(ServletContextEvent event) {
                throw new NoClassDefFoundError("com/example/Missing");
            }
        });

        assertDoesNotThrow(() -> registry.fireContextDestroyed());

        assertEquals(List.of("contextDestroyed:survivor"), events);
    }

    @Test
    void aFailingAttributeListenerDoesNotBreakTheMutationThatTriggeredIt() {
        // Attribute listeners are observers, not participants: unlike HttpSessionBindingListener, which is
        // the value's own resource protocol, nothing is left half-bound when one of these fails.
        registry.addListener(new RecordingListener("survivor"));
        registry.addListener(new HttpSessionAttributeListener() {
            @Override
            public void attributeAdded(HttpSessionBindingEvent event) {
                throw new IllegalStateException("boom");
            }
        });

        registry.fireSessionAttributeAdded(session, "k", "v");

        assertEquals(List.of("sessionAttributeAdded:survivor"), events);
    }
}

package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionTrackingMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultNettyServletContextTest {

    private DefaultNettyServletContext context;

    @BeforeEach
    void setUp() {
        context = new DefaultNettyServletContext();
    }

    // --- Attribute methods ---

    @Test
    void shouldReturnNullForUnknownAttribute() {
        assertNull(context.getAttribute("unknown"));
    }

    @Test
    void shouldSetAndGetAttribute() {
        context.setAttribute("key", "value");

        assertEquals("value", context.getAttribute("key"));
    }

    @Test
    void shouldRemoveAttributeWhenSetToNull() {
        context.setAttribute("key", "value");

        context.setAttribute("key", null);

        assertNull(context.getAttribute("key"));
        assertFalse(collectNames(context.getAttributeNames()).contains("key"));
    }

    @Test
    void shouldRemoveAttribute() {
        context.setAttribute("key", "value");

        context.removeAttribute("key");

        assertNull(context.getAttribute("key"));
    }

    @Test
    void shouldEnumerateAttributeNames() {
        context.setAttribute("a", 1);
        context.setAttribute("b", 2);

        var names = collectNames(context.getAttributeNames());

        assertTrue(names.contains("a"));
        assertTrue(names.contains("b"));
        assertEquals(2, names.size());
    }

    // --- Init parameter methods ---

    @Test
    void shouldReturnNullForUnknownInitParameter() {
        assertNull(context.getInitParameter("unknown"));
    }

    @Test
    void shouldSetInitParameterAndReturnTrue() {
        assertTrue(context.setInitParameter("key", "value"));

        assertEquals("value", context.getInitParameter("key"));
    }

    @Test
    void shouldReturnFalseWhenInitParameterAlreadySet() {
        context.setInitParameter("key", "value");

        assertFalse(context.setInitParameter("key", "other"));
        assertEquals("value", context.getInitParameter("key"));
    }

    @Test
    void shouldEnumerateInitParameterNames() {
        context.setInitParameter("x", "1");
        context.setInitParameter("y", "2");

        var names = collectNames(context.getInitParameterNames());

        assertTrue(names.contains("x"));
        assertTrue(names.contains("y"));
        assertEquals(2, names.size());
    }

    // --- Servlet registration ---

    @Test
    void shouldAddServletByClassName() {
        var registration = context.addServlet("myServlet", "com.example.MyServlet");

        assertNotNull(registration);
        assertInstanceOf(ServletRegistration.Dynamic.class, registration);
        assertEquals("myServlet", registration.getName());
        assertEquals("com.example.MyServlet", registration.getClassName());
    }

    @Test
    void shouldAddServletByInstance() {
        Servlet servlet = new StubServlet();

        var registration = context.addServlet("myServlet", servlet);

        assertNotNull(registration);
        assertEquals("myServlet", registration.getName());
        assertEquals(StubServlet.class.getName(), registration.getClassName());
    }

    @Test
    void shouldAddServletByClass() {
        var registration = context.addServlet("myServlet", StubServlet.class);

        assertNotNull(registration);
        assertEquals("myServlet", registration.getName());
        assertEquals(StubServlet.class.getName(), registration.getClassName());
    }

    @Test
    void shouldGetServletRegistrationByName() {
        context.addServlet("myServlet", "com.example.MyServlet");

        var registration = context.getServletRegistration("myServlet");

        assertNotNull(registration);
        assertEquals("myServlet", registration.getName());
    }

    @Test
    void shouldReturnNullForUnknownServletRegistration() {
        assertNull(context.getServletRegistration("unknown"));
    }

    @Test
    void shouldReturnAllServletRegistrations() {
        context.addServlet("s1", "com.example.S1");
        context.addServlet("s2", "com.example.S2");

        Map<String, ? extends ServletRegistration> registrations = context.getServletRegistrations();

        assertEquals(2, registrations.size());
        assertTrue(registrations.containsKey("s1"));
        assertTrue(registrations.containsKey("s2"));
    }

    @Test
    void shouldReturnUnmodifiableServletRegistrations() {
        context.addServlet("s1", "com.example.S1");

        var registrations = context.getServletRegistrations();

        assertThrows(UnsupportedOperationException.class, () -> registrations.put("s2", null));
    }

    // --- Servlet registration init parameters ---

    @Test
    void shouldSetAndGetServletRegistrationInitParameter() {
        var registration = context.addServlet("s", "com.example.S");

        assertTrue(registration.setInitParameter("p1", "v1"));
        assertEquals("v1", registration.getInitParameter("p1"));
    }

    @Test
    void shouldReturnFalseForDuplicateServletRegistrationInitParameter() {
        var registration = context.addServlet("s", "com.example.S");
        registration.setInitParameter("p1", "v1");

        assertFalse(registration.setInitParameter("p1", "v2"));
        assertEquals("v1", registration.getInitParameter("p1"));
    }

    @Test
    void shouldReturnNullForUnknownServletRegistrationInitParameter() {
        var registration = context.addServlet("s", "com.example.S");

        assertNull(registration.getInitParameter("unknown"));
    }

    @Test
    void shouldBulkSetServletRegistrationInitParameters() {
        var registration = context.addServlet("s", "com.example.S");
        registration.setInitParameter("existing", "old");

        var conflicts = registration.setInitParameters(Map.of("existing", "new", "fresh", "value"));

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.contains("existing"));
        assertEquals("old", registration.getInitParameter("existing"));
        assertEquals("value", registration.getInitParameter("fresh"));
    }

    @Test
    void shouldReturnUnmodifiableServletRegistrationInitParameters() {
        var registration = context.addServlet("s", "com.example.S");
        registration.setInitParameter("p1", "v1");

        var params = registration.getInitParameters();

        assertThrows(UnsupportedOperationException.class, () -> params.put("p2", "v2"));
    }

    // --- Servlet registration mappings ---

    @Test
    void shouldAddAndGetServletMappings() {
        var registration = context.addServlet("s", "com.example.S");

        registration.addMapping("/a", "/b");

        var mappings = registration.getMappings();
        assertEquals(2, mappings.size());
        assertTrue(mappings.contains("/a"));
        assertTrue(mappings.contains("/b"));
    }

    @Test
    void shouldReturnEmptyMappingsInitially() {
        var registration = context.addServlet("s", "com.example.S");

        assertTrue(registration.getMappings().isEmpty());
    }

    // --- Servlet registration stub methods ---

    @Test
    void shouldReturnNullRunAsRole() {
        var registration = context.addServlet("s", "com.example.S");

        assertNull(registration.getRunAsRole());
    }

    @Test
    void shouldAcceptSetLoadOnStartup() {
        var registration = context.addServlet("s", "com.example.S");

        assertDoesNotThrow(() -> registration.setLoadOnStartup(1));
    }

    @Test
    void shouldAcceptSetServletSecurity() {
        var registration = context.addServlet("s", "com.example.S");

        var result = registration.setServletSecurity(new jakarta.servlet.ServletSecurityElement());

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldAcceptSetMultipartConfig() {
        var registration = context.addServlet("s", "com.example.S");

        assertDoesNotThrow(() -> registration.setMultipartConfig(new jakarta.servlet.MultipartConfigElement("/tmp")));
    }

    @Test
    void shouldAcceptSetRunAsRole() {
        var registration = context.addServlet("s", "com.example.S");

        assertDoesNotThrow(() -> registration.setRunAsRole("admin"));
    }

    @Test
    void shouldAcceptSetAsyncSupported() {
        var registration = context.addServlet("s", "com.example.S");

        assertDoesNotThrow(() -> registration.setAsyncSupported(true));
    }

    // --- Filter registration ---

    @Test
    void shouldAddFilterByClassName() {
        var registration = context.addFilter("myFilter", "com.example.MyFilter");

        assertNotNull(registration);
        assertInstanceOf(FilterRegistration.Dynamic.class, registration);
        assertEquals("myFilter", registration.getName());
        assertEquals("com.example.MyFilter", registration.getClassName());
    }

    @Test
    void shouldAddFilterByInstance() {
        Filter filter = new StubFilter();

        var registration = context.addFilter("myFilter", filter);

        assertNotNull(registration);
        assertEquals("myFilter", registration.getName());
        assertEquals(StubFilter.class.getName(), registration.getClassName());
    }

    @Test
    void shouldAddFilterByClass() {
        var registration = context.addFilter("myFilter", StubFilter.class);

        assertNotNull(registration);
        assertEquals("myFilter", registration.getName());
        assertEquals(StubFilter.class.getName(), registration.getClassName());
    }

    @Test
    void shouldGetFilterRegistrationByName() {
        context.addFilter("myFilter", "com.example.MyFilter");

        var registration = context.getFilterRegistration("myFilter");

        assertNotNull(registration);
        assertEquals("myFilter", registration.getName());
    }

    @Test
    void shouldReturnNullForUnknownFilterRegistration() {
        assertNull(context.getFilterRegistration("unknown"));
    }

    @Test
    void shouldReturnAllFilterRegistrations() {
        context.addFilter("f1", "com.example.F1");
        context.addFilter("f2", "com.example.F2");

        Map<String, ? extends FilterRegistration> registrations = context.getFilterRegistrations();

        assertEquals(2, registrations.size());
        assertTrue(registrations.containsKey("f1"));
        assertTrue(registrations.containsKey("f2"));
    }

    @Test
    void shouldReturnUnmodifiableFilterRegistrations() {
        context.addFilter("f1", "com.example.F1");

        var registrations = context.getFilterRegistrations();

        assertThrows(UnsupportedOperationException.class, () -> registrations.put("f2", null));
    }

    // --- Filter registration init parameters ---

    @Test
    void shouldSetAndGetFilterRegistrationInitParameter() {
        var registration = context.addFilter("f", "com.example.F");

        assertTrue(registration.setInitParameter("p1", "v1"));
        assertEquals("v1", registration.getInitParameter("p1"));
    }

    @Test
    void shouldReturnUnmodifiableFilterRegistrationInitParameters() {
        var registration = context.addFilter("f", "com.example.F");
        registration.setInitParameter("p1", "v1");

        var params = registration.getInitParameters();

        assertThrows(UnsupportedOperationException.class, () -> params.put("p2", "v2"));
    }

    // --- Filter registration stub methods ---

    @Test
    void shouldAcceptAddMappingForServletNames() {
        var registration = context.addFilter("f", "com.example.F");

        assertDoesNotThrow(() -> registration.addMappingForServletNames(null, false, "servletA"));
    }

    @Test
    void shouldReturnEmptyServletNameMappings() {
        var registration = context.addFilter("f", "com.example.F");

        assertTrue(registration.getServletNameMappings().isEmpty());
    }

    @Test
    void shouldAcceptAddMappingForUrlPatterns() {
        var registration = context.addFilter("f", "com.example.F");

        assertDoesNotThrow(() -> registration.addMappingForUrlPatterns(null, false, "/api/*"));
    }

    @Test
    void shouldReturnEmptyUrlPatternMappings() {
        var registration = context.addFilter("f", "com.example.F");

        assertTrue(registration.getUrlPatternMappings().isEmpty());
    }

    @Test
    void shouldStoreUrlPatternMappings() {
        var registration = context.addFilter("f", new StubFilter());

        registration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/api/*", "/admin/*");

        assertTrue(registration.getUrlPatternMappings().containsAll(List.of("/api/*", "/admin/*")));
    }

    @Test
    void shouldDefaultDispatcherTypesToRequestWhenNullPassed() {
        var registration = context.addFilter("f", new StubFilter());

        registration.addMappingForUrlPatterns(null, false, "/*");

        var registered = context.getRegisteredFilters();
        assertEquals(1, registered.size());
        assertTrue(registered.get(0).dispatcherTypes().contains(DispatcherType.REQUEST));
    }

    // --- Executable filter registrations (getRegisteredFilters) ---

    @Test
    void shouldRetainFilterInstanceInRegisteredFilters() {
        Filter filter = new StubFilter();
        var registration = context.addFilter("myFilter", filter);
        registration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");

        var registered = context.getRegisteredFilters();

        assertEquals(1, registered.size());
        assertEquals("myFilter", registered.get(0).name());
        assertEquals(filter, registered.get(0).filter());
    }

    @Test
    void shouldPreserveRegistrationOrderInRegisteredFilters() {
        context.addFilter("first", new StubFilter());
        context.addFilter("second", new StubFilter());

        var registered = context.getRegisteredFilters();

        assertEquals(List.of("first", "second"), registered.stream().map(RegisteredFilter::name).toList());
    }

    @Test
    void shouldExcludeClassNameOnlyRegistrationsFromRegisteredFilters() {
        context.addFilter("instanceFilter", new StubFilter());
        context.addFilter("classNameFilter", "com.example.MyFilter");
        context.addFilter("classFilter", StubFilter.class);

        var registered = context.getRegisteredFilters();

        assertEquals(1, registered.size());
        assertEquals("instanceFilter", registered.get(0).name());
    }

    // --- Resource methods (all return null) ---

    @Test
    void shouldReturnNullForGetResource() throws MalformedURLException {
        assertNull(context.getResource("/index.html"));
    }

    @Test
    void shouldReturnNullForGetResourceAsStream() {
        assertNull(context.getResourceAsStream("/index.html"));
    }

    @Test
    void shouldReturnNullForGetResourcePaths() {
        assertNull(context.getResourcePaths("/"));
    }

    @Test
    void shouldReturnNullForGetRealPath() {
        assertNull(context.getRealPath("/"));
    }

    // --- Simple getters ---

    @Test
    void shouldReturnEmptyContextPath() {
        assertEquals("", context.getContextPath());
    }

    @Test
    void shouldReturnServletContextName() {
        assertEquals("NettyServletContext", context.getServletContextName());
    }

    @Test
    void shouldReturnMajorVersion() {
        assertEquals(6, context.getMajorVersion());
    }

    @Test
    void shouldReturnMinorVersion() {
        assertEquals(0, context.getMinorVersion());
    }

    @Test
    void shouldReturnEffectiveMajorVersion() {
        assertEquals(6, context.getEffectiveMajorVersion());
    }

    @Test
    void shouldReturnEffectiveMinorVersion() {
        assertEquals(0, context.getEffectiveMinorVersion());
    }

    @Test
    void shouldReturnServerInfo() {
        assertEquals("Netty-Loom", context.getServerInfo());
    }

    @Test
    void shouldReturnClassLoader() {
        assertNotNull(context.getClassLoader());
    }

    // --- Session support (issue #13) ---

    @Test
    void shouldOwnASessionManager() {
        assertNotNull(context.getSessionManager());
        assertSame(context.getSessionManager(), context.getSessionManager());
    }

    @Test
    void shouldExposeTheSessionManagersCookieConfig() {
        assertSame(context.getSessionManager().getCookieConfig(), context.getSessionCookieConfig());
    }

    @Test
    void shouldDefaultSessionTimeoutToThirtyMinutes() {
        assertEquals(30, context.getSessionTimeout());
    }

    @Test
    void shouldRoundTripSessionTimeoutThroughMinutes() {
        context.setSessionTimeout(5);

        assertEquals(5, context.getSessionTimeout());
        assertEquals(300, context.getSessionManager().getDefaultMaxInactiveInterval(),
            "ServletContext speaks minutes; the manager stores seconds");
    }

    @Test
    void shouldRoundSubMinuteTimeoutUpToOneMinute() {
        // The manager keeps seconds so a 30s configuration is honoured exactly. Reporting that through
        // the minutes-based ServletContext API must round up: truncating to 0 would mean "never
        // expires", turning a 30-second timeout into an infinite one.
        context.getSessionManager().setDefaultMaxInactiveInterval(30);

        assertEquals(1, context.getSessionTimeout());
    }

    @Test
    void shouldClampAnImplausiblyLargeSessionTimeoutRatherThanWrap() {
        // Unchecked int arithmetic here would make Integer.MAX_VALUE minutes store -60 seconds, which
        // isExpired reads as "never expires", and 35_791_395 minutes wrap to a plausible small positive
        // timeout. web.xml's <session-timeout> and any ServletContextInitializer can reach this, and
        // Integer.MAX_VALUE is a common way to spell "effectively never".
        context.setSessionTimeout(Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, context.getSessionManager().getDefaultMaxInactiveInterval());
    }

    @Test
    void shouldTreatZeroSessionTimeoutAsNeverExpires() {
        context.setSessionTimeout(0);

        assertEquals(0, context.getSessionTimeout());
        assertEquals(0, context.getSessionManager().getDefaultMaxInactiveInterval());
    }

    @Test
    void shouldTrackSessionsByCookieOnly() {
        assertEquals(Set.of(SessionTrackingMode.COOKIE), context.getDefaultSessionTrackingModes());
        assertEquals(Set.of(SessionTrackingMode.COOKIE), context.getEffectiveSessionTrackingModes());
    }

    @Test
    void shouldAcceptCookieSessionTrackingMode() {
        context.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE));

        assertEquals(Set.of(SessionTrackingMode.COOKIE), context.getEffectiveSessionTrackingModes());
    }

    @Test
    void shouldAcceptEmptySessionTrackingModes() {
        context.setSessionTrackingModes(Set.of());

        assertTrue(context.getEffectiveSessionTrackingModes().isEmpty(),
            "An empty set legitimately disables the session cookie");
    }

    @Test
    void shouldRejectUrlSessionTrackingMode() {
        // Silently ignoring it would leave sessions quietly broken with no signal, so fail fast the way
        // the factory already does for server.ssl.*.
        var thrown = assertThrows(IllegalArgumentException.class,
            () -> context.setSessionTrackingModes(Set.of(SessionTrackingMode.URL)));

        assertTrue(thrown.getMessage().contains("URL"), "The message should name the rejected mode");
        assertTrue(thrown.getMessage().contains("server.servlet.session.tracking-modes"),
            "The message should name the property to change");
    }

    @Test
    void shouldRejectSslSessionTrackingMode() {
        assertThrows(IllegalArgumentException.class,
            () -> context.setSessionTrackingModes(Set.of(SessionTrackingMode.SSL)));
    }

    @Test
    void shouldLeaveTrackingModesUnchangedWhenRejectingAnUnsupportedMode() {
        assertThrows(IllegalArgumentException.class,
            () -> context.setSessionTrackingModes(EnumSet.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL)));

        assertEquals(Set.of(SessionTrackingMode.COOKIE), context.getEffectiveSessionTrackingModes());
    }

    @Test
    void shouldRejectSessionReconfigurationOnceInitialized() {
        // setSessionTrackingModes and setSessionTimeout carry the same "already initialized" clause as
        // the SessionCookieConfig setters. The tracking modes are read live on every request, so
        // disabling cookie tracking at runtime would silently stop issuing and reading session cookies.
        context.getSessionManager().markContextInitialized();

        assertThrows(IllegalStateException.class, () -> context.setSessionTrackingModes(Set.of()));
        assertThrows(IllegalStateException.class, () -> context.setSessionTimeout(5));
    }

    @Test
    void shouldStillReportSessionConfigurationOnceInitialized() {
        context.setSessionTimeout(5);
        context.getSessionManager().markContextInitialized();

        assertEquals(5, context.getSessionTimeout());
        assertEquals(Set.of(SessionTrackingMode.COOKIE), context.getEffectiveSessionTrackingModes());
    }

    @Test
    void shouldCloseTheSessionManager() {
        context.getSessionManager().create();
        assertEquals(1, context.getSessionManager().size());

        context.close();

        assertEquals(0, context.getSessionManager().size());
    }

    // --- Cookie SameSite policy (issue #85) ---

    @Test
    void shouldDefaultToNoCookieSameSiteResolver() {
        assertSame(NettyCookieSameSiteResolver.NO_OPINION, context.getCookieSameSiteResolver());
    }

    @Test
    void shouldReadBackTheConfiguredCookieSameSiteResolver() {
        NettyCookieSameSiteResolver resolver = cookie -> "Strict";

        context.setCookieSameSiteResolver(resolver);

        assertSame(resolver, context.getCookieSameSiteResolver());
    }

    // --- Listeners (issue #17) ---

    @Test
    void shouldRegisterListenerByInstance() {
        var listener = new CountingContextListener();

        context.addListener(listener);
        context.fireContextInitialized();

        assertEquals(1, listener.initialized);
    }

    @Test
    void shouldRegisterListenerByClass() {
        StubContextListener.reset();

        context.addListener(StubContextListener.class);
        context.fireContextInitialized();

        assertEquals(1, StubContextListener.initialized);
    }

    @Test
    void shouldRegisterListenerByClassName() {
        StubContextListener.reset();

        context.addListener(StubContextListener.class.getName());
        context.fireContextInitialized();

        assertEquals(1, StubContextListener.initialized);
    }

    @Test
    void shouldRejectAnUnknownListenerClassName() {
        assertThrows(IllegalArgumentException.class, () -> context.addListener("com.example.NoSuchListener"));
    }

    @Test
    void shouldRejectAClassNameThatIsNotAListener() {
        assertThrows(IllegalArgumentException.class, () -> context.addListener(String.class.getName()));
    }

    @Test
    void shouldRejectAListenerClassThatCannotBeInstantiated() {
        // The overload that wraps createListener was untested: emptying its catch block left the suite
        // green and turned addListener(Class) into a silent no-op, which is exactly the "application
        // starts believing it is wired up" failure the registry throws to avoid.
        var thrown = assertThrows(IllegalArgumentException.class,
            () -> context.addListener(UninstantiableListener.class));

        assertTrue(thrown.getMessage().contains(UninstantiableListener.class.getName()),
            "the message must name the class that could not be built; got " + thrown.getMessage());
    }

    @Test
    void shouldRejectCreatingAListenerOfNoSupportedType() {
        var thrown = assertThrows(IllegalArgumentException.class,
            () -> context.createListener(UnsupportedListener.class));

        // The message, not just the type: it names the accepted interfaces, and it is the half a third
        // call site could drop by throwing a bare IllegalArgumentException of its own.
        assertTrue(thrown.getMessage().contains(ServletContextListener.class.getName()),
            "the message must name the types that are accepted; got " + thrown.getMessage());
    }

    @Test
    void shouldCreateListenerWithItsNoArgConstructor() throws Exception {
        assertInstanceOf(StubContextListener.class, context.createListener(StubContextListener.class));
    }

    @Test
    void shouldReportListenerInstantiationFailureAsServletException() {
        // The Jakarta contract: createListener wraps the reflective failure, so a caller sees why the
        // class could not be built rather than a bare InvocationTargetException.
        var thrown = assertThrows(jakarta.servlet.ServletException.class,
            () -> context.createListener(UninstantiableListener.class));

        assertTrue(thrown.getMessage().contains(UninstantiableListener.class.getName()),
            "The message should name the class that could not be instantiated; got " + thrown.getMessage());
    }

    @Test
    void shouldFreezeEveryComponentFromOneCall() {
        // "Startup is over" is one fact, so the context fans it out rather than each caller naming every
        // freezable component -- the same shape NettySessionManager already uses to reach the cookie
        // config. A fourth component then needs no new call site, and none can be silently missed.
        context.markInitialized();

        assertThrows(IllegalStateException.class, () -> context.addListener(new CountingContextListener()),
            "listener registration must be frozen");
        assertThrows(IllegalStateException.class, () -> context.setSessionTimeout(5),
            "session configuration must be frozen");
        assertThrows(IllegalStateException.class, () -> context.getSessionCookieConfig().setName("X"),
            "the session cookie configuration must be frozen");
    }

    @Test
    void shouldFireContextInitializedOnlyOnce() {
        var listener = new CountingContextListener();
        context.addListener(listener);

        context.fireContextInitialized();
        context.fireContextInitialized();

        assertEquals(1, listener.initialized);
    }

    @Test
    void shouldFireContextDestroyedOnClose() {
        var listener = new CountingContextListener();
        context.addListener(listener);
        context.fireContextInitialized();

        context.close();

        assertEquals(1, listener.destroyed);
    }

    @Test
    void shouldFireContextDestroyedOnlyOnceAcrossRepeatedCloses() {
        // close() is an idempotent backstop -- SessionStoreLifecycle.stop() and the bean-destruction
        // callback both reach it -- so the event must not be delivered twice.
        var listener = new CountingContextListener();
        context.addListener(listener);
        context.fireContextInitialized();

        context.close();
        context.close();

        assertEquals(1, listener.destroyed);
    }

    @Test
    void shouldNotFireContextDestroyedWhenStartupNeverCompleted() {
        // An initializer can fail before fireContextInitialized runs; close() still executes as the
        // backstop, and destroying listeners that were never initialized would be worse than doing nothing.
        var listener = new CountingContextListener();
        context.addListener(listener);

        context.close();

        assertEquals(0, listener.destroyed);
    }

    @Test
    void shouldFireContextDestroyedAfterTheSessionStoreIsDrained() {
        // Tomcat stops the Manager before listenerStop, so a listener auditing live sessions on the way
        // out sees the store already emptied rather than a half-drained one.
        var sessionsAtDestroy = new int[]{-1};
        context.addListener(new ServletContextListener() {
            @Override
            public void contextDestroyed(ServletContextEvent event) {
                sessionsAtDestroy[0] = context.getSessionManager().size();
            }
        });
        context.fireContextInitialized();
        context.getSessionManager().create();

        context.close();

        assertEquals(0, sessionsAtDestroy[0]);
    }

    @Test
    void shouldReinitializeListenersWhenTheContextIsRestarted() {
        // ApplicationContext.start() after stop(), and CRaC restore, replay the stop phase. Leaving the
        // listeners destroyed would mean an application serving normally with every listener torn down.
        var listener = new CountingContextListener();
        context.addListener(listener);
        context.fireContextInitialized();

        context.close();
        context.open();

        assertEquals(2, listener.initialized);
        assertEquals(1, listener.destroyed);
    }

    @Test
    void shouldNotFireContextInitializedOnOpenWithoutAPriorClose() {
        // open() runs on every start, including the first -- where the factory has already fired the
        // event. Firing again would double-initialize every listener on a normal boot.
        var listener = new CountingContextListener();
        context.addListener(listener);
        context.fireContextInitialized();

        context.open();

        assertEquals(1, listener.initialized);
    }

    @Test
    void shouldFireContextAttributeAddedThenReplaced() {
        var events = new java.util.ArrayList<String>();
        context.addListener(new ServletContextAttributeListener() {
            @Override
            public void attributeAdded(ServletContextAttributeEvent event) {
                events.add("added:" + event.getName() + "=" + event.getValue());
            }

            @Override
            public void attributeReplaced(ServletContextAttributeEvent event) {
                events.add("replaced:" + event.getName() + "=" + event.getValue());
            }
        });

        context.setAttribute("user", "alice");
        context.setAttribute("user", "bob");

        // The replacement reports the displaced value, not the new one.
        assertEquals(List.of("added:user=alice", "replaced:user=alice"), events);
    }

    @Test
    void shouldFireContextAttributeRemovedForBothRemovalForms() {
        var removed = new java.util.ArrayList<String>();
        context.addListener(new ServletContextAttributeListener() {
            @Override
            public void attributeRemoved(ServletContextAttributeEvent event) {
                removed.add(event.getName() + "=" + event.getValue());
            }
        });

        context.setAttribute("explicit", "a");
        context.removeAttribute("explicit");
        context.setAttribute("viaNull", "b");
        context.setAttribute("viaNull", null);

        assertEquals(List.of("explicit=a", "viaNull=b"), removed);
    }

    @Test
    void shouldNotFireContextAttributeRemovedForAnAbsentName() {
        var removed = new java.util.ArrayList<String>();
        context.addListener(new ServletContextAttributeListener() {
            @Override
            public void attributeRemoved(ServletContextAttributeEvent event) {
                removed.add(event.getName());
            }
        });

        context.removeAttribute("never-set");

        assertTrue(removed.isEmpty(), "removing an absent attribute changes nothing, so it notifies nothing");
    }

    private static List<String> collectNames(Enumeration<String> enumeration) {
        var names = new java.util.ArrayList<String>();
        enumeration.asIterator().forEachRemaining(names::add);
        return names;
    }

    // --- Stub types for testing ---

    private static class StubServlet implements Servlet {
        @Override public void init(jakarta.servlet.ServletConfig config) {}
        @Override public jakarta.servlet.ServletConfig getServletConfig() { return null; }
        @Override public void service(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {}
        @Override public String getServletInfo() { return null; }
        @Override public void destroy() {}
    }

    private static class StubFilter implements Filter {
        @Override public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response,
                                       jakarta.servlet.FilterChain chain) {}
    }

    private static class CountingContextListener implements ServletContextListener {

        private int initialized;
        private int destroyed;

        @Override public void contextInitialized(ServletContextEvent event) { initialized++; }
        @Override public void contextDestroyed(ServletContextEvent event) { destroyed++; }
    }

    /** Registered by class and by name, so it counts statically -- the container builds its own instance. */
    static class StubContextListener implements ServletContextListener {

        private static int initialized;

        static void reset() { initialized = 0; }

        @Override public void contextInitialized(ServletContextEvent event) { initialized++; }
    }

    static class UninstantiableListener implements ServletContextListener {

        UninstantiableListener() {
            throw new IllegalStateException("cannot be built");
        }
    }

    /** A legal EventListener, but none of the seven types addListener accepts. */
    static class UnsupportedListener implements java.util.EventListener {
    }
}

package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

public class DefaultNettyServletContext implements NettyServletContext {

    private static final Logger log = LoggerFactory.getLogger(DefaultNettyServletContext.class);

    // ServletContext expresses the session timeout in minutes while HttpSession and the manager use
    // seconds, so the two session-timeout methods below convert. This names that factor.
    private static final int SECONDS_PER_MINUTE = 60;

    // Constructed here rather than injected: both need a ServletContext -- the manager for
    // HttpSession.getServletContext(), the registry to name the source of every event it fires -- so a
    // separate bean would mean a cycle or two-phase init.
    private final NettySessionManager sessionManager = new NettySessionManager(this);
    private final NettyListenerRegistry listeners = new NettyListenerRegistry(this);

    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> initParameters = new ConcurrentHashMap<>();
    private final Map<String, ServletRegistration> servletRegistrations = new LinkedHashMap<>();
    private final Map<String, FilterRegistration> filterRegistrations = new LinkedHashMap<>();
    // Immutable snapshot of the executable filters, built once and reused on the per-request hot
    // path. Invalidated (set to null) whenever a filter is registered, so a late registration
    // rebuilds it on next read. Registration is single-threaded at startup; reads happen after
    // server start, so the volatile field is sufficient for safe publication.
    private volatile List<RegisteredFilter> registeredFiltersSnapshot;
    private volatile String contextPath = ROOT_CONTEXT_PATH;
    private volatile NettyCookieSameSiteResolver cookieSameSiteResolver = NettyCookieSameSiteResolver.NONE;
    // Atomic because the transition, not the value, is what must happen once: close() is reachable from
    // both SessionStoreLifecycle.stop() and the bean-destruction backstop, and each event is owed exactly
    // one delivery. Same idiom as NettyHttpSession.markInvalidated.
    private final AtomicReference<ListenerState> listenerState = new AtomicReference<>(ListenerState.NEW);

    /**
     * Where the listener lifecycle stands. {@code NEW} until {@code contextInitialized} has fired,
     * {@code STARTED} between the two events, {@code STOPPED} once {@code contextDestroyed} has -- and
     * only {@code STOPPED} is a state {@link #open()} re-initializes from, so a first start (which the
     * factory has already initialized) is left alone.
     */
    private enum ListenerState { NEW, STARTED, STOPPED }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object object) {
        if (object == null) {
            removeAttribute(name);
            return;
        }
        Object previous = attributes.put(name, object);
        if (previous == null) {
            listeners.fireContextAttributeAdded(name, object);
        } else {
            listeners.fireContextAttributeReplaced(name, previous);
        }
    }

    @Override
    public void removeAttribute(String name) {
        Object removed = attributes.remove(name);
        if (removed != null) {
            listeners.fireContextAttributeRemoved(name, removed);
        }
    }

    @Override
    public String getInitParameter(String name) {
        return initParameters.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        return initParameters.putIfAbsent(name, value) == null;
    }

    private ServletRegistration.Dynamic registerServlet(String servletName, String className) {
        var registration = new NettyServletRegistration(servletName, className);
        servletRegistrations.put(servletName, registration);
        return registration;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        return registerServlet(servletName, className);
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        return registerServlet(servletName, servlet.getClass().getName());
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
        return registerServlet(servletName, servletClass.getName());
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName) {
        return servletRegistrations.get(servletName);
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return Collections.unmodifiableMap(servletRegistrations);
    }

    private FilterRegistration.Dynamic registerFilter(String filterName, String className, Filter filter) {
        var registration = new NettyFilterRegistration(filterName, className, filter);
        filterRegistrations.put(filterName, registration);
        registeredFiltersSnapshot = null;
        return registration;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        return registerFilter(filterName, className, null);
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        return registerFilter(filterName, filter.getClass().getName(), filter);
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        return registerFilter(filterName, filterClass.getName(), null);
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        return filterRegistrations.get(filterName);
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return Collections.unmodifiableMap(filterRegistrations);
    }

    @Override
    public List<RegisteredFilter> getRegisteredFilters() {
        List<RegisteredFilter> snapshot = registeredFiltersSnapshot;
        if (snapshot == null) {
            snapshot = buildRegisteredFilters();
            registeredFiltersSnapshot = snapshot;
        }
        return snapshot;
    }

    private List<RegisteredFilter> buildRegisteredFilters() {
        var registered = new ArrayList<RegisteredFilter>();
        for (var registration : filterRegistrations.values()) {
            if (registration instanceof NettyFilterRegistration filterRegistration && filterRegistration.filter != null) {
                registered.add(filterRegistration.toRegisteredFilter());
            }
        }
        return Collections.unmodifiableList(registered);
    }

    // --- Listeners: the registry is the single owner, this is registration only (issue #17) ---

    @Override
    public NettyListenerRegistry getListenerRegistry() {
        return listeners;
    }

    @Override
    public void addListener(String className) {
        addListener(loadListenerClass(className));
    }

    @Override
    public <T extends EventListener> void addListener(T t) {
        listeners.addListener(t);
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {
        try {
            listeners.addListener(createListener(listenerClass));
        } catch (ServletException e) {
            // This overload declares no checked exception, so the instantiation failure has to arrive as
            // an unchecked one. IllegalArgumentException is what Tomcat raises here, and it is what
            // ServletContext.addListener already documents for a class it cannot use.
            throw new IllegalArgumentException("Listener class " + listenerClass.getName()
                + " could not be instantiated", e);
        }
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
        // The spec puts the same wrong-type clause on createListener as on addListener, and Tomcat runs
        // the checks before instantiating. Without it an application following the documented
        // create-customize-then-addListener idiom gets no signal until the later addListener call.
        listeners.requireSupportedType(clazz);
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            // Covers every way this fails: no no-arg constructor (NoSuchMethodException), abstract or an
            // interface (InstantiationException), inaccessible (IllegalAccessException), and a
            // constructor that throws (InvocationTargetException).
            throw new ServletException("Failed to instantiate listener " + clazz.getName(), e);
        }
    }

    private Class<? extends EventListener> loadListenerClass(String className) {
        try {
            return getClassLoader().loadClass(className).asSubclass(EventListener.class);
        } catch (ClassNotFoundException | ClassCastException e) {
            throw new IllegalArgumentException("Listener class " + className
                + " could not be loaded as a java.util.EventListener", e);
        }
    }

    @Override
    public URL getResource(String path) throws MalformedURLException {
        return null;
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        return null;
    }

    @Override
    public Set<String> getResourcePaths(String path) {
        return null;
    }

    @Override
    public String getRealPath(String path) {
        return null;
    }

    @Override
    public void setContextPath(String contextPath) {
        this.contextPath = contextPath == null ? ROOT_CONTEXT_PATH : contextPath;
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public void setCookieSameSiteResolver(NettyCookieSameSiteResolver resolver) {
        this.cookieSameSiteResolver = resolver;
    }

    @Override
    public NettyCookieSameSiteResolver getCookieSameSiteResolver() {
        return cookieSameSiteResolver;
    }

    // --- Sessions: the manager is the single owner, this is pure delegation (issue #13) ---

    @Override
    public NettySessionManager getSessionManager() {
        return sessionManager;
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        return sessionManager.getCookieConfig();
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
        sessionManager.setTrackingModes(sessionTrackingModes);
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return sessionManager.getDefaultTrackingModes();
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return sessionManager.getTrackingModes();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Rounds up rather than truncating: the manager stores seconds, so a 30-second timeout would
     * otherwise report as 0 minutes -- which in this API means "never expires".
     */
    @Override
    public int getSessionTimeout() {
        return Math.ceilDiv(sessionManager.getDefaultMaxInactiveInterval(), SECONDS_PER_MINUTE);
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        // Widened before the multiply and clamped, for the same reason the Duration overload on the
        // manager is: the wrap lands on a plausible-looking value rather than an obviously wrong one.
        // The manager's setter enforces the shared post-initialization freeze.
        sessionManager.setDefaultMaxInactiveInterval(
            Math.clamp((long) sessionTimeout * SECONDS_PER_MINUTE, Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    @Override
    public void markInitialized() {
        sessionManager.markContextInitialized();
        listeners.markInitialized();
    }

    @Override
    public void fireContextInitialized() {
        if (listenerState.getAndSet(ListenerState.STARTED) != ListenerState.STARTED) {
            listeners.fireContextInitialized();
        }
    }

    @Override
    public void close() {
        sessionManager.close();
        // After the store is drained, matching Tomcat: StandardContext.stopInternal() stops the Manager
        // and only then runs listenerStop, so a listener auditing live sessions on the way out is not
        // handed a half-drained store. Guarded by the transition, not by a flag read: close() is reached
        // both from SessionStoreLifecycle.stop() and from the bean-destruction backstop, and a startup
        // that failed before fireContextInitialized has nothing to destroy.
        if (listenerState.compareAndSet(ListenerState.STARTED, ListenerState.STOPPED)) {
            listeners.fireContextDestroyed();
        }
    }

    @Override
    public void open() {
        sessionManager.open();
        // Only from STOPPED. open() also runs on a first start, where the factory has already fired
        // contextInitialized, and re-firing there would double-initialize every listener on a normal boot.
        if (listenerState.get() == ListenerState.STOPPED) {
            fireContextInitialized();
        }
    }

    @Override
    public String getServletContextName() {
        return "NettyServletContext";
    }

    @Override
    public int getMajorVersion() {
        return 6;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return 6;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return 0;
    }

    @Override
    public String getServerInfo() {
        return "Netty-Loom";
    }

    @Override
    public ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    public void log(String msg) {
        log.info(msg);
    }

    @Override
    public void log(String message, Throwable throwable) {
        log.error(message, throwable);
    }

    // --- Registration implementations ---

    private abstract static class AbstractNettyRegistration {

        private final String name;
        private final String className;
        private final Map<String, String> initParameters = new LinkedHashMap<>();

        AbstractNettyRegistration(String name, String className) {
            this.name = name;
            this.className = className;
        }

        public String getName() {
            return name;
        }

        public String getClassName() {
            return className;
        }

        public boolean setInitParameter(String name, String value) {
            return initParameters.putIfAbsent(name, value) == null;
        }

        public String getInitParameter(String name) {
            return initParameters.get(name);
        }

        public Set<String> setInitParameters(Map<String, String> initParameters) {
            var conflicts = new HashSet<String>();
            for (var entry : initParameters.entrySet()) {
                if (this.initParameters.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                    conflicts.add(entry.getKey());
                }
            }
            return conflicts;
        }

        public Map<String, String> getInitParameters() {
            return Collections.unmodifiableMap(initParameters);
        }

        public void setAsyncSupported(boolean isAsyncSupported) {
        }
    }

    private static class NettyServletRegistration extends AbstractNettyRegistration implements ServletRegistration.Dynamic {

        private final Set<String> mappings = new LinkedHashSet<>();

        NettyServletRegistration(String name, String className) {
            super(name, className);
        }

        @Override
        public Set<String> addMapping(String... urlPatterns) {
            Collections.addAll(mappings, urlPatterns);
            return Collections.emptySet();
        }

        @Override
        public Collection<String> getMappings() {
            return Collections.unmodifiableSet(mappings);
        }

        @Override
        public String getRunAsRole() {
            return null;
        }

        @Override
        public void setLoadOnStartup(int loadOnStartup) {
        }

        @Override
        public Set<String> setServletSecurity(jakarta.servlet.ServletSecurityElement constraint) {
            return Collections.emptySet();
        }

        @Override
        public void setMultipartConfig(jakarta.servlet.MultipartConfigElement multipartConfig) {
        }

        @Override
        public void setRunAsRole(String roleName) {
        }
    }

    private static class NettyFilterRegistration extends AbstractNettyRegistration implements FilterRegistration.Dynamic {

        private final Filter filter;
        private final Set<String> urlPatterns = new LinkedHashSet<>();
        private final EnumSet<DispatcherType> dispatcherTypes = EnumSet.noneOf(DispatcherType.class);

        NettyFilterRegistration(String name, String className, Filter filter) {
            super(name, className);
            this.filter = filter;
        }

        @Override
        public void addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes,
                                               boolean isMatchAfter, String... servletNames) {
            // Servlet-name filter mappings are not executed by this server (only URL-pattern
            // mappings are). Warn so the unsupported mapping is observable instead of a silent no-op.
            if (servletNames != null && servletNames.length > 0) {
                log.warn("Filter '{}' declares servlet-name mappings {} which are not supported "
                    + "and will be ignored; map it by URL pattern instead.", getName(), List.of(servletNames));
            }
        }

        @Override
        public Collection<String> getServletNameMappings() {
            return Collections.emptySet();
        }

        @Override
        public void addMappingForUrlPatterns(EnumSet<DispatcherType> dispatcherTypes,
                                              boolean isMatchAfter, String... urlPatterns) {
            // The servlet spec defaults to REQUEST when no dispatcher types are supplied; Spring
            // Boot always passes EnumSet.of(REQUEST), but the spec allows null.
            this.dispatcherTypes.addAll(dispatcherTypes == null ? EnumSet.of(DispatcherType.REQUEST) : dispatcherTypes);
            Collections.addAll(this.urlPatterns, urlPatterns);
        }

        @Override
        public Collection<String> getUrlPatternMappings() {
            return Collections.unmodifiableSet(urlPatterns);
        }

        RegisteredFilter toRegisteredFilter() {
            // EnumSet.copyOf(EnumSet) handles the empty case; an unmapped filter has no URL
            // patterns either, so it never matches regardless of dispatcher types.
            return new RegisteredFilter(getName(), filter, new LinkedHashSet<>(urlPatterns),
                EnumSet.copyOf(dispatcherTypes));
        }
    }
}

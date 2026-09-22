package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
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
import java.util.Deque;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultNettyServletContext implements NettyServletContext {

    private static final Logger log = LoggerFactory.getLogger(DefaultNettyServletContext.class);

    /**
     * ServletContext expresses the session timeout in minutes while HttpSession and the manager use
     * seconds, so the session-timeout methods below convert.
     */
    private static final int SECONDS_PER_MINUTE = 60;
    private static final String DEFAULT_SERVLET_CONTEXT_NAME = "NettyServletContext";

    /**
     * Constructed here rather than injected: both need this ServletContext, so a separate bean would
     * mean a cycle or two-phase init.
     */
    private final NettySessionManager sessionManager = new NettySessionManager(this);
    private final NettyListenerRegistry listeners = new NettyListenerRegistry(this);

    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> initParameters = new ConcurrentHashMap<>();
    private final Map<String, ServletRegistration> servletRegistrations = new LinkedHashMap<>();
    private final Map<String, FilterRegistration> filterRegistrations = new LinkedHashMap<>();
    /**
     * Rebuilt on the next read after a filter registration sets it to null. Registration is
     * single-threaded at startup and reads follow server start, so volatile suffices for publication.
     */
    private volatile List<RegisteredFilter> registeredFiltersSnapshot;
    private volatile String contextPath = ROOT_CONTEXT_PATH;
    private volatile String servletContextName = DEFAULT_SERVLET_CONTEXT_NAME;
    private volatile Map<String, String> mimeMappings = Map.of();
    private volatile NettyDispatchFactory dispatchFactory;
    private volatile NettyCookieSameSiteResolver cookieSameSiteResolver = NettyCookieSameSiteResolver.NO_OPINION;
    private volatile NettyErrorPageResolver errorPageResolver = NettyErrorPageResolver.NO_PAGES;
    /**
     * Atomic because the transition must happen once: close() is reachable from both
     * ServletContextLifecycle.stop() and the bean-destruction backstop, and each event is owed one delivery.
     */
    private final AtomicReference<ListenerState> listenerState = new AtomicReference<>(ListenerState.NEW);
    /**
     * Appended after each init that returned, so a startup that failed midway destroys only that prefix;
     * claimed by pollLast.
     */
    private final Deque<RegisteredFilter> initializedFilters = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean servletInitialized = new AtomicBoolean();
    private volatile String servletName;
    private volatile Servlet servlet;

    /**
     * Only {@code STOPPED} is a state {@link #open()} re-initializes from, so a first start -- which the
     * factory has already initialized -- is left alone.
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
            snapshot = newRegisteredFilters();
            registeredFiltersSnapshot = snapshot;
        }
        return snapshot;
    }

    private List<RegisteredFilter> newRegisteredFilters() {
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
            /*
             * This overload declares no checked exception, so the instantiation failure has to arrive as
             * an unchecked one. IllegalArgumentException is what Tomcat raises here, and it is what
             * ServletContext.addListener already documents for a class it cannot use.
             */
            throw new IllegalArgumentException("Listener class " + listenerClass.getName()
                + " could not be instantiated", e);
        }
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
        /*
         * Type check before the constructor, unlike Tomcat's ApplicationContext.createListener, which
         * instantiates first: a class of the wrong type fails with the IllegalArgumentException the spec
         * names for it, never with a ServletException from a constructor that had no business running.
         */
        listeners.requireSupportedType(clazz);
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
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
    public void setMimeMappings(Map<String, String> mimeMappings) {
        var lowerCased = new HashMap<String, String>();
        mimeMappings.forEach((extension, mimeType) -> lowerCased.put(extension.toLowerCase(Locale.ROOT), mimeType));
        this.mimeMappings = Collections.unmodifiableMap(lowerCased);
    }

    @Override
    public String getMimeType(String file) {
        if (file == null) {
            return null;
        }
        int period = file.lastIndexOf('.');
        if (period < 0) {
            return null;
        }
        return mimeMappings.get(file.substring(period + 1).toLowerCase(Locale.ROOT));
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

    @Override
    public void setErrorPageResolver(NettyErrorPageResolver resolver) {
        this.errorPageResolver = resolver;
    }

    @Override
    public NettyErrorPageResolver getErrorPageResolver() {
        return errorPageResolver;
    }

    @Override
    public void setDispatchFactory(NettyDispatchFactory dispatchFactory) {
        this.dispatchFactory = dispatchFactory;
    }

    @Override
    public NettyDispatchFactory getDispatchFactory() {
        return dispatchFactory;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return dispatchFactory.forContextPath(path);
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
     * Rounds up rather than truncating: the manager stores seconds, so a 30-second timeout would
     * otherwise report as 0 minutes -- which in this API means "never expires".
     */
    @Override
    public int getSessionTimeout() {
        return Math.ceilDiv(sessionManager.getDefaultMaxInactiveInterval(), SECONDS_PER_MINUTE);
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        /*
         * Widened before the multiply and clamped rather than left to int arithmetic: the wrap lands on a
         * plausible-looking value rather than an obviously wrong one.
         */
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
    public void initializeFilters() throws ServletException {
        for (RegisteredFilter registeredFilter : getRegisteredFilters()) {
            registeredFilter.filter().init(new NettyFilterConfig(registeredFilter.name(), this));
            initializedFilters.addLast(registeredFilter);
        }
    }

    @Override
    public void initializeServlet(String servletName, Servlet servlet) throws ServletException {
        this.servletName = servletName;
        this.servlet = servlet;
        servlet.init(new NettyServletConfig(servletName, this));
        servletInitialized.set(true);
    }

    @Override
    public void close() {
        /*
         * Servlet, filters back to front, store, listeners: the order of StandardContext.stopInternal()
         * (wrappers, filterStop, Manager, listenerStop), so a listener auditing live sessions on the way
         * out is not handed a half-drained store, and ServletContextListener.contextDestroyed's promise
         * that every servlet and filter is already destroyed holds.
         */
        if (servletInitialized.getAndSet(false)) {
            destroyQuietly("Servlet '" + servletName + "'", servlet::destroy);
        }
        RegisteredFilter filter;
        while ((filter = initializedFilters.pollLast()) != null) {
            destroyQuietly("Filter '" + filter.name() + "'", filter.filter()::destroy);
        }
        sessionManager.close();
        if (listenerState.compareAndSet(ListenerState.STARTED, ListenerState.STOPPED)) {
            listeners.fireContextDestroyed();
        }
    }

    private static void destroyQuietly(String description, Runnable destroy) {
        try {
            destroy.run();
        } catch (Throwable failure) {
            /*
             * Logged and passed over, as Tomcat's ApplicationFilterConfig.release() does: teardown has no
             * caller in a position to handle it, and one failure must not strand what is left to destroy.
             */
            NettyListenerRegistry.rethrowIfFatal(failure);
            log.warn("{} failed to destroy", description, failure);
        }
    }

    @Override
    public boolean isClosed() {
        return sessionManager.isClosed();
    }

    @Override
    public void open() {
        sessionManager.open();
        /*
         * Only from STOPPED. open() also runs on a first start, where the factory has already fired
         * contextInitialized and initialized the filters and the servlet, and repeating that there would
         * double-initialize every one of them on a normal boot.
         */
        if (listenerState.get() == ListenerState.STOPPED) {
            fireContextInitialized();
            try {
                initializeFilters();
                if (servlet != null) {
                    initializeServlet(servletName, servlet);
                }
            } catch (ServletException e) {
                throw new IllegalStateException("Failed to re-initialize filters and servlet on restart", e);
            }
        }
    }

    @Override
    public void setServletContextName(String servletContextName) {
        this.servletContextName = servletContextName == null ? DEFAULT_SERVLET_CONTEXT_NAME : servletContextName;
    }

    @Override
    public String getServletContextName() {
        return servletContextName;
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
            /*
             * Servlet-name filter mappings are not executed by this server (only URL-pattern
             * mappings are). Warn so the unsupported mapping is observable instead of a silent no-op.
             */
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
            /*
             * The servlet spec defaults to REQUEST when no dispatcher types are supplied; Spring
             * Boot always passes EnumSet.of(REQUEST), but the spec allows null.
             */
            this.dispatcherTypes.addAll(dispatcherTypes == null ? EnumSet.of(DispatcherType.REQUEST) : dispatcherTypes);
            Collections.addAll(this.urlPatterns, urlPatterns);
        }

        @Override
        public Collection<String> getUrlPatternMappings() {
            return Collections.unmodifiableSet(urlPatterns);
        }

        RegisteredFilter toRegisteredFilter() {
            // EnumSet.copyOf(EnumSet) handles the empty case, unlike the Collection overload.
            return new RegisteredFilter(getName(), filter, new LinkedHashSet<>(urlPatterns),
                EnumSet.copyOf(dispatcherTypes));
        }
    }
}

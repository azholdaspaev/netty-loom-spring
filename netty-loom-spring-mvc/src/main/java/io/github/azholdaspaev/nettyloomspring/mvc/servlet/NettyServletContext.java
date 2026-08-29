package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface NettyServletContext extends ServletContext, AutoCloseable {

    /**
     * Context path value meaning the application is mounted at the server root. Matches Spring Boot's
     * {@code ContextPath} normalization, which represents the root as an empty string, never {@code "/"}.
     */
    String ROOT_CONTEXT_PATH = "";

    /**
     * The executable filter registrations -- those backed by a live {@link Filter} instance -- in
     * registration order, which already reflects Spring Boot's {@code @Order} resolution.
     */
    default List<RegisteredFilter> getRegisteredFilters() {
        return List.of();
    }

    /**
     * Sets the context path resolved from {@code server.servlet.context-path}; {@code null} means root.
     */
    void setContextPath(String contextPath);

    /**
     * Sets the container-wide {@code SameSite} policy for cookies the application writes (issue #85): the
     * route Boot's {@code CookieSameSiteSupplier} beans take to the request path, since no servlet API
     * carries them.
     */
    void setCookieSameSiteResolver(NettyCookieSameSiteResolver resolver);

    NettyCookieSameSiteResolver getCookieSameSiteResolver();

    /**
     * Sets the error pages a failed request is answered with (issue #38): the route Boot's
     * {@code ErrorPage} registrations take to the dispatch, since no servlet API carries them.
     */
    void setErrorPageResolver(NettyErrorPageResolver resolver);

    NettyErrorPageResolver getErrorPageResolver();

    /**
     * Binds what a dispatch runs through. Published here rather than kept by the dispatcher that builds
     * it because {@link #getRequestDispatcher(String)} is answered from this package, with no request in
     * hand to carry it, and unwrapping one at forward time would only find whatever wrapper a filter
     * installed.
     */
    void setDispatchFactory(NettyDispatchFactory dispatchFactory);

    NettyDispatchFactory getDispatchFactory();

    /**
     * The store backing {@code HttpServletRequest.getSession(...)}: the servlet API exposes only session
     * configuration, never the store, but the request needs it to resolve and create them.
     */
    NettySessionManager getSessionManager();

    /**
     * The container-registered listeners, as filled by {@code addListener} (issue #17). The Jakarta
     * contract only accepts listeners; the session store and the dispatcher have to fire into them.
     */
    NettyListenerRegistry getListenerRegistry();

    /**
     * Announces that startup has reached {@code ServletContextListener.contextInitialized}: after the
     * {@code ServletContextInitializer}s have registered everything and before filters and servlets are
     * initialized, the order the servlet spec and Tomcat's
     * {@code listenerStart -> filterStart -> loadOnStartup} both use. Idempotent, and paired with
     * {@link #close()} so the two context events stay balanced across a stop/start cycle.
     */
    void fireContextInitialized();

    /**
     * Declares startup over, freezing every part of the context that may only be configured before the
     * application serves traffic -- session settings, the session cookie, and listener registration.
     * Called after filter and servlet initialization, not with {@link #fireContextInitialized()}: Tomcat
     * is still in {@code STARTING_PREP} during those, so a {@code Filter.init} that configures the
     * session cookie works there and must work here.
     */
    void markInitialized();

    /**
     * Releases whatever the context holds open. On the interface rather than only the implementation
     * because owning a background thread is part of this seam: whoever holds a
     * {@code NettyServletContext} is responsible for closing it.
     */
    @Override
    default void close() {
    }

    /**
     * Reverses {@link #close()}, so the context can serve sessions again after a stop/start cycle: Spring
     * restarts the stop phase on {@code ApplicationContext.start()}, {@code restart()} and CRaC restore,
     * and without this every {@code getSession(true)} after a restart fails.
     */
    default void open() {
    }

    @Override
    default String getContextPath() {
        throw new UnsupportedOperationException();
    }

    @Override
    default ServletContext getContext(String uripath) {
        throw new UnsupportedOperationException();
    }

    @Override
    default int getMajorVersion() {
        throw new UnsupportedOperationException();
    }

    @Override
    default int getMinorVersion() {
        throw new UnsupportedOperationException();
    }

    @Override
    default int getEffectiveMajorVersion() {
        throw new UnsupportedOperationException();
    }

    @Override
    default int getEffectiveMinorVersion() {
        throw new UnsupportedOperationException();
    }

    @Override
    default String getMimeType(String file) {
        throw new UnsupportedOperationException();
    }

    @Override
    default Set<String> getResourcePaths(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    default URL getResource(String path) throws MalformedURLException {
        throw new UnsupportedOperationException();
    }

    @Override
    default InputStream getResourceAsStream(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    default RequestDispatcher getRequestDispatcher(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    default RequestDispatcher getNamedDispatcher(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    default String getRealPath(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    default String getServerInfo() {
        throw new UnsupportedOperationException();
    }

    @Override
    default String getInitParameter(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    default Enumeration<String> getInitParameterNames() {
        throw new UnsupportedOperationException();
    }

    @Override
    default boolean setInitParameter(String name, String value) {
        throw new UnsupportedOperationException();
    }

    @Override
    default Object getAttribute(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    default Enumeration<String> getAttributeNames() {
        throw new UnsupportedOperationException();
    }

    @Override
    default void setAttribute(String name, Object object) {
        throw new UnsupportedOperationException();
    }

    @Override
    default void removeAttribute(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    default String getServletContextName() {
        throw new UnsupportedOperationException();
    }

    @Override
    default ServletRegistration.Dynamic addServlet(String servletName, String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    default ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        throw new UnsupportedOperationException();
    }

    @Override
    default ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    default ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        throw new UnsupportedOperationException();
    }

    @Override
    default <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    default ServletRegistration getServletRegistration(String servletName) {
        throw new UnsupportedOperationException();
    }

    @Override
    default Map<String, ? extends ServletRegistration> getServletRegistrations() {
        throw new UnsupportedOperationException();
    }

    @Override
    default FilterRegistration.Dynamic addFilter(String filterName, String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    default FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    default FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    default <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    default FilterRegistration getFilterRegistration(String filterName) {
        throw new UnsupportedOperationException();
    }

    @Override
    default Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        throw new UnsupportedOperationException();
    }

    @Override
    default SessionCookieConfig getSessionCookieConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    default void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
        throw new UnsupportedOperationException();
    }

    @Override
    default Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        throw new UnsupportedOperationException();
    }

    @Override
    default Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        throw new UnsupportedOperationException();
    }

    @Override
    default void addListener(String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    default <T extends EventListener> void addListener(T t) {
        throw new UnsupportedOperationException();
    }

    @Override
    default void addListener(Class<? extends EventListener> listenerClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    default <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    default JspConfigDescriptor getJspConfigDescriptor() {
        throw new UnsupportedOperationException();
    }

    @Override
    default ClassLoader getClassLoader() {
        throw new UnsupportedOperationException();
    }

    @Override
    default void declareRoles(String... roleNames) {
        throw new UnsupportedOperationException();
    }

    @Override
    default String getVirtualServerName() {
        throw new UnsupportedOperationException();
    }

    @Override
    default int getSessionTimeout() {
        throw new UnsupportedOperationException();
    }

    @Override
    default void setSessionTimeout(int sessionTimeout) {
        throw new UnsupportedOperationException();
    }

    @Override
    default String getRequestCharacterEncoding() {
        throw new UnsupportedOperationException();
    }

    @Override
    default void setRequestCharacterEncoding(String encoding) {
        throw new UnsupportedOperationException();
    }

    @Override
    default String getResponseCharacterEncoding() {
        throw new UnsupportedOperationException();
    }

    @Override
    default void setResponseCharacterEncoding(String encoding) {
        throw new UnsupportedOperationException();
    }
}

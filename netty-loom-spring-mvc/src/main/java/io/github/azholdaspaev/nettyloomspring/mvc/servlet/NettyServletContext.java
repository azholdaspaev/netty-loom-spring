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
     * Context path value meaning the application is mounted at the server root (no prefix). Matches
     * Spring Boot's {@code ContextPath} normalization, which represents the root as an empty string
     * (never {@code "/"}). Used as the sentinel by {@link #getContextPath()} and the request path
     * logic that strips this prefix.
     */
    String ROOT_CONTEXT_PATH = "";

    /**
     * Returns the executable filter registrations (those backed by a live {@link Filter}
     * instance) in registration order, which already reflects Spring Boot's {@code @Order}
     * resolution. Not part of the Jakarta {@link ServletContext} contract.
     */
    default List<RegisteredFilter> getRegisteredFilters() {
        return List.of();
    }

    /**
     * Sets the context path this server is mounted under, as resolved from
     * {@code server.servlet.context-path}. A {@code null} value means the root context ({@code ""}).
     * Not part of the Jakarta {@link ServletContext} contract.
     */
    void setContextPath(String contextPath);

    /**
     * The store backing {@code HttpServletRequest.getSession(...)}. Not part of the Jakarta
     * {@code ServletContext} contract: the servlet API exposes only configuration of sessions, never
     * the store itself, but the request needs it to resolve and create them.
     */
    NettySessionManager getSessionManager();

    /**
     * The container-registered listeners, as filled by {@code addListener} (issue #17). Not part of the
     * Jakarta {@code ServletContext} contract, which only accepts listeners and never hands them back:
     * the session store and the request dispatcher both have to fire into them.
     */
    NettyListenerRegistry getListenerRegistry();

    /**
     * Announces that startup has reached the point where {@code ServletContextListener.contextInitialized}
     * is due -- after the {@code ServletContextInitializer}s have registered everything, and before
     * filters and servlets are initialized, which is the order the servlet spec and Tomcat's
     * {@code listenerStart -> filterStart -> loadOnStartup} both use.
     *
     * <p>Idempotent, and paired with {@link #close()}: together they keep the two context events balanced
     * across a stop/start cycle. Not part of the Jakarta {@code ServletContext} contract -- the container
     * owns this transition, so nothing in the servlet API names it.
     *
     * <p>Abstract rather than an empty default, unlike {@link #close()} and {@link #open()}. Those are
     * genuinely optional -- a context holding nothing open needs no teardown -- whereas an
     * implementation that silently skipped this would fire no {@code contextInitialized}, and then no
     * {@code contextDestroyed} either, with nothing to signal either omission.
     */
    void fireContextInitialized();

    /**
     * Releases whatever the context holds open -- today the session sweeper thread. On the interface
     * rather than only the implementation because owning a background thread is part of this seam:
     * whoever holds a {@code NettyServletContext} is responsible for closing it.
     */
    @Override
    default void close() {
    }

    /**
     * Reverses {@link #close()}, so the context can serve sessions again after a stop/start cycle.
     *
     * <p>Needed because {@code close()} is no longer only a teardown: it runs in the <em>stop</em> phase,
     * and Spring restarts that phase on {@code ApplicationContext.start()}, {@code restart()} and CRaC
     * restore. Without this the store stays permanently closed and every {@code getSession(true)} after a
     * restart fails.
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

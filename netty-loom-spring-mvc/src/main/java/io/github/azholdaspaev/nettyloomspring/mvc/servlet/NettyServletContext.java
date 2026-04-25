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
import java.util.Map;
import java.util.Set;

public interface NettyServletContext extends ServletContext {

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

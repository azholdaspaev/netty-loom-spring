package io.github.azholdaspaev.nettyloom.mvc.servlet;

import io.github.azholdaspaev.nettyloom.mvc.exception.NotImplementedException;
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
import java.util.Enumeration;
import java.util.EventListener;
import java.util.Map;
import java.util.Set;

public interface NettyServletContext extends ServletContext {

    @Override
    default String getContextPath() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default ServletContext getContext(String uripath) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default int getMajorVersion() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default int getMinorVersion() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default int getEffectiveMajorVersion() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default int getEffectiveMinorVersion() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default String getMimeType(String file) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default Set<String> getResourcePaths(String path) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default InputStream getResourceAsStream(String path) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default RequestDispatcher getRequestDispatcher(String path) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default RequestDispatcher getNamedDispatcher(String name) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default String getRealPath(String path) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default String getServerInfo() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default boolean setInitParameter(String name, String value) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default Object getAttribute(String name) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default Enumeration<String> getAttributeNames() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default void setAttribute(String name, Object object) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default void removeAttribute(String name) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default String getServletContextName() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default ServletRegistration.Dynamic addServlet(String servletName, String className) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default ServletRegistration getServletRegistration(String servletName) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default Map<String, ? extends ServletRegistration> getServletRegistrations() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default FilterRegistration.Dynamic addFilter(String filterName, String className) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default FilterRegistration getFilterRegistration(String filterName) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default SessionCookieConfig getSessionCookieConfig() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default void addListener(String className) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default <T extends EventListener> void addListener(T t) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default void addListener(Class<? extends EventListener> listenerClass) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default JspConfigDescriptor getJspConfigDescriptor() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default ClassLoader getClassLoader() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default void declareRoles(String... roleNames) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default String getVirtualServerName() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default int getSessionTimeout() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default void setSessionTimeout(int sessionTimeout) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default String getRequestCharacterEncoding() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default void setRequestCharacterEncoding(String encoding) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default String getResponseCharacterEncoding() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    default void setResponseCharacterEncoding(String encoding) {
        throw new NotImplementedException("Not implemented");
    }
}

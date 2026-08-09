package io.github.azholdaspaev.nettyloomspring.autoconfigure.listener.app;

import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequestAttributeEvent;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Counts every event the container is expected to deliver, keyed by event name. One class implementing
 * the listener interfaces rather than a fixture per interface: it is also the assertion that a single
 * instance is filed under every type it qualifies for, which is how Spring Security's
 * {@code HttpSessionEventPublisher} (an {@code HttpSessionListener} <em>and</em> an
 * {@code HttpSessionIdListener}) arrives. Counters are concurrent because requests are served on
 * virtual threads and the session sweeper is a thread of its own.
 */
public class RecordingListener implements ServletContextListener, ServletContextAttributeListener,
    ServletRequestListener, ServletRequestAttributeListener, HttpSessionListener,
    HttpSessionAttributeListener, HttpSessionIdListener {

    private final ConcurrentMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    private void count(String event) {
        counts.computeIfAbsent(event, key -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * Keyed by attribute name too: {@code DispatcherServlet} sets request attributes of its own on every
     * dispatch, each legitimately notifying, so a bare counter measures Spring's traffic not the fixture's.
     */
    private void countAttribute(String event, String name) {
        count(event);
        count(event + ":" + name);
    }

    public int countOf(String event) {
        AtomicInteger counter = counts.get(event);
        return counter == null ? 0 : counter.get();
    }

    public Map<String, Integer> snapshot() {
        return counts.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
    }

    /**
     * Clearing also wipes {@code contextInitialized}, which fires once per application and so cannot be
     * observed again by a later test method.
     */
    public void reset() {
        counts.clear();
    }

    @Override
    public void contextInitialized(ServletContextEvent event) {
        count("contextInitialized");
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        count("contextDestroyed");
    }

    @Override
    public void attributeAdded(ServletContextAttributeEvent event) {
        countAttribute("contextAttributeAdded", event.getName());
    }

    @Override
    public void attributeReplaced(ServletContextAttributeEvent event) {
        countAttribute("contextAttributeReplaced", event.getName());
    }

    @Override
    public void attributeRemoved(ServletContextAttributeEvent event) {
        countAttribute("contextAttributeRemoved", event.getName());
    }

    @Override
    public void requestInitialized(ServletRequestEvent event) {
        count("requestInitialized");
    }

    @Override
    public void requestDestroyed(ServletRequestEvent event) {
        count("requestDestroyed");
    }

    @Override
    public void attributeAdded(ServletRequestAttributeEvent event) {
        countAttribute("requestAttributeAdded", event.getName());
    }

    @Override
    public void attributeReplaced(ServletRequestAttributeEvent event) {
        countAttribute("requestAttributeReplaced", event.getName());
    }

    @Override
    public void attributeRemoved(ServletRequestAttributeEvent event) {
        countAttribute("requestAttributeRemoved", event.getName());
    }

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        count("sessionCreated");
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        count("sessionDestroyed");
    }

    @Override
    public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
        count("sessionIdChanged");
    }

    @Override
    public void attributeAdded(HttpSessionBindingEvent event) {
        countAttribute("sessionAttributeAdded", event.getName());
    }

    @Override
    public void attributeReplaced(HttpSessionBindingEvent event) {
        countAttribute("sessionAttributeReplaced", event.getName());
    }

    @Override
    public void attributeRemoved(HttpSessionBindingEvent event) {
        countAttribute("sessionAttributeRemoved", event.getName());
    }
}

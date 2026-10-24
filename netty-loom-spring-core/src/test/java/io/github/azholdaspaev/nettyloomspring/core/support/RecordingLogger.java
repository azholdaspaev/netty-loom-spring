package io.github.azholdaspaev.nettyloomspring.core.support;

import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;
import org.slf4j.event.Level;
import org.slf4j.event.LoggingEvent;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.spi.LoggingEventAware;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps each fluent-API event whole, key-value pairs included. slf4j-simple, the test binding, folds
 * those pairs into the message text, so asserting on its output could not tell a pair from prose.
 */
public final class RecordingLogger extends LegacyAbstractLogger implements LoggingEventAware {

    public final List<LoggingEvent> events = new ArrayList<>();

    public RecordingLogger() {
        this.name = "recording";
    }

    @Override
    public void log(LoggingEvent event) {
        events.add(event);
    }

    public LoggingEvent single() {
        if (events.size() != 1) {
            throw new AssertionError("expected exactly one event, got " + events.size() + ": " + messages());
        }
        return events.getFirst();
    }

    public List<String> messages() {
        return events.stream().map(LoggingEvent::getMessage).toList();
    }

    public static Map<String, Object> keyValues(LoggingEvent event) {
        Map<String, Object> pairs = new LinkedHashMap<>();
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            pairs.put(pair.key, pair.value);
        }
        return pairs;
    }

    @Override
    public boolean isTraceEnabled() {
        return true;
    }

    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern, Object[] arguments,
                                               Throwable throwable) {
        throw new AssertionError("only the fluent API is expected here; got: " + messagePattern);
    }
}

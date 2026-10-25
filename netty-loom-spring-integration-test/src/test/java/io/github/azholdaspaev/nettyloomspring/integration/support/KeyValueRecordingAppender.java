package io.github.azholdaspaev.nettyloomspring.integration.support;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.event.KeyValuePair;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class KeyValueRecordingAppender extends AppenderBase<ILoggingEvent> {

    // Concurrent: appended on event loops, read on the test thread.
    private final Queue<Map<String, Object>> recordedKeyValues = new ConcurrentLinkedQueue<>();

    @Override
    protected void append(ILoggingEvent event) {
        Map<String, Object> pairs = new LinkedHashMap<>();
        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                pairs.put(pair.key, pair.value);
            }
        }
        recordedKeyValues.add(pairs);
    }

    public List<Map<String, Object>> getRecordedKeyValues() {
        return List.copyOf(recordedKeyValues);
    }
}

package io.github.azholdaspaev.nettyloomspring.integration.support;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class MdcRecordingAppender extends AppenderBase<ILoggingEvent> {

    // Concurrent: appended on dispatch threads, read on the test thread.
    private final Queue<Map<String, String>> recordedMdcs = new ConcurrentLinkedQueue<>();

    @Override
    protected void append(ILoggingEvent event) {
        // Copied here, on the logging thread: getMDCPropertyMap resolves MDC lazily from its caller's thread.
        recordedMdcs.add(Map.copyOf(event.getMDCPropertyMap()));
    }

    public List<Map<String, String>> getRecordedMdcs() {
        return List.copyOf(recordedMdcs);
    }
}

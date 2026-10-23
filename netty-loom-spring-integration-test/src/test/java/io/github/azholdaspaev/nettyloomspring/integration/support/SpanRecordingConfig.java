package io.github.azholdaspaev.nettyloomspring.integration.support;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

@TestConfiguration(proxyBeanMethods = false)
public class SpanRecordingConfig {

    // A field, not a SpanExporter bean: Boot's BatchSpanProcessor would export every span to it a second time.
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    @Bean
    SpanProcessor recordingSpanProcessor() {
        return SimpleSpanProcessor.create(exporter);
    }

    public List<SpanData> getFinishedSpans() {
        return exporter.getFinishedSpanItems();
    }
}

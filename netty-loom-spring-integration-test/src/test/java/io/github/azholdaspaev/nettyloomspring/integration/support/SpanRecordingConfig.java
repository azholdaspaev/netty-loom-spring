package io.github.azholdaspaev.nettyloomspring.integration.support;

import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@TestConfiguration(proxyBeanMethods = false)
public class SpanRecordingConfig {

    // A field, not a SpanExporter bean: Boot's BatchSpanProcessor would export every span to it a second time.
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    // Concurrent: added on dispatch threads, read on the test thread.
    private final Set<String> traceIdsAfterChain = ConcurrentHashMap.newKeySet();

    @Bean
    SpanProcessor recordingSpanProcessor() {
        return SimpleSpanProcessor.create(exporter);
    }

    @Bean
    ServletRequestListener traceIdRecordingListener() {
        return new ServletRequestListener() {
            @Override
            public void requestDestroyed(ServletRequestEvent event) {
                if (event.getServletRequest() instanceof HttpServletRequest request) {
                    ServerHttpObservationFilter.findObservationContext(request)
                        .map(context -> context.<TracingContext>get(TracingContext.class))
                        .ifPresent(tracing -> traceIdsAfterChain.add(tracing.getSpan().context().traceId()));
                }
            }
        };
    }

    public List<SpanData> getFinishedSpans() {
        return exporter.getFinishedSpanItems();
    }

    public Set<String> getTraceIdsAfterChain() {
        return Set.copyOf(traceIdsAfterChain);
    }
}

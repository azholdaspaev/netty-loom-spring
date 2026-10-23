package io.github.azholdaspaev.nettyloomspring.integration.tracing;

import io.github.azholdaspaev.nettyloomspring.integration.support.BaseIntegrationTest;
import io.github.azholdaspaev.nettyloomspring.integration.support.SpanRecordingConfig;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TracingTest extends BaseIntegrationTest {

    private static final Duration RECORDING_DEADLINE = Duration.ofSeconds(5);

    @Autowired
    private SpanRecordingConfig spanRecording;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldContinueIncomingTraceInOneServerSpan() {
        String traceId = IdGenerator.random().generateTraceId();
        String parentSpanId = IdGenerator.random().generateSpanId();

        restTestClient.get().uri("/items/42")
            .header("traceparent", traceparent(traceId, parentSpanId))
            .exchange()
            .expectStatus().isOk();

        List<SpanData> serverSpans = awaitServerSpans(traceId);
        assertEquals(1, serverSpans.size(), "the request must end exactly one server span in its trace");
        assertEquals(parentSpanId, serverSpans.getFirst().getParentSpanId(),
            "the server span must be parented on the span the traceparent header names");
    }

    private List<SpanData> awaitServerSpans(String traceId) {
        return await().atMost(RECORDING_DEADLINE).until(() -> spanRecording.getFinishedSpans().stream()
            .filter(span -> span.getTraceId().equals(traceId) && span.getKind() == SpanKind.SERVER)
            .toList(), spans -> !spans.isEmpty());
    }

    private static String traceparent(String traceId, String parentSpanId) {
        return "00-" + traceId + "-" + parentSpanId + "-01";
    }
}

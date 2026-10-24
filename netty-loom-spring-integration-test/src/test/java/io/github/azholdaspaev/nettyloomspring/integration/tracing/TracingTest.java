package io.github.azholdaspaev.nettyloomspring.integration.tracing;

import ch.qos.logback.classic.Logger;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpAccessLogHandler;
import io.github.azholdaspaev.nettyloomspring.integration.support.BaseIntegrationTest;
import io.github.azholdaspaev.nettyloomspring.integration.support.KeyValueRecordingAppender;
import io.github.azholdaspaev.nettyloomspring.integration.support.MdcRecordingAppender;
import io.github.azholdaspaev.nettyloomspring.integration.support.SpanRecordingConfig;
import io.github.azholdaspaev.nettyloomspring.integration.web.ItemController;
import io.github.azholdaspaev.nettyloomspring.integration.web.ItemUnavailableException;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracingTest extends BaseIntegrationTest {

    private static final Duration RECORDING_DEADLINE = Duration.ofSeconds(5);

    private static final String SAMPLED = "01";

    private static final String NOT_SAMPLED = "00";

    private final MdcRecordingAppender controllerLog = new MdcRecordingAppender();

    private final KeyValueRecordingAppender accessLog = new KeyValueRecordingAppender();

    @Autowired
    private SpanRecordingConfig spanRecording;

    @BeforeEach
    void attachControllerLog() {
        controllerLog.start();
        controllerLogger().addAppender(controllerLog);
    }

    @AfterEach
    void detachControllerLog() {
        controllerLogger().detachAppender(controllerLog);
        controllerLog.stop();
    }

    @BeforeEach
    void attachAccessLog() {
        accessLog.start();
        accessLogger().addAppender(accessLog);
    }

    @AfterEach
    void detachAccessLog() {
        accessLogger().detachAppender(accessLog);
        accessLog.stop();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldContinueIncomingTraceInOneServerSpan() {
        String traceId = IdGenerator.random().generateTraceId();
        String parentSpanId = IdGenerator.random().generateSpanId();

        restTestClient.get().uri("/items/42")
            .header("traceparent", traceparent(traceId, parentSpanId, SAMPLED))
            .exchange()
            .expectStatus().isOk();

        List<SpanData> serverSpans = awaitServerSpans(traceId);
        assertEquals(1, serverSpans.size(), "the request must end exactly one server span in its trace");
        assertEquals(parentSpanId, serverSpans.getFirst().getParentSpanId(),
            "the server span must be parented on the span the traceparent header names");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void shouldPutTraceInMdcOfControllerLog() {
        String traceId = IdGenerator.random().generateTraceId();

        restTestClient.get().uri("/items/42")
            .header("traceparent", traceparent(traceId, IdGenerator.random().generateSpanId(), SAMPLED))
            .exchange()
            .expectStatus().isOk();

        Map<String, String> mdc = await().atMost(RECORDING_DEADLINE).until(() -> controllerLog.getRecordedMdcs()
            .stream().filter(recorded -> traceId.equals(recorded.get("traceId"))).findFirst(), Optional::isPresent)
            .orElseThrow();
        SpanData server = awaitServerSpans(traceId).getFirst();
        assertTrue(descendsFrom(mdc.get("spanId"), server.getSpanId(), spansOf(traceId)),
            "the controller's MDC spanId " + mdc.get("spanId") + " must name the server span "
                + server.getSpanId() + " or a span beneath it; trace was " + spansOf(traceId));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldEndOneSpanWith500AcrossErrorDispatch() {
        String traceId = IdGenerator.random().generateTraceId();

        restTestClient.get().uri("/items/7/unavailable")
            .header("traceparent", traceparent(traceId, IdGenerator.random().generateSpanId(), SAMPLED))
            .exchange()
            .expectStatus().isEqualTo(500);

        List<SpanData> serverSpans = awaitServerSpans(traceId);
        assertEquals(1, serverSpans.size(),
            "the error-page dispatch must not end a second server span in the request's trace");
        SpanData server = serverSpans.getFirst();
        assertEquals("500", server.getAttributes().get(AttributeKey.stringKey("status")),
            "the server span must carry the status the client received");
        assertTrue(server.getEvents().stream().anyMatch(event -> event.getName().equals("exception")
                && ItemUnavailableException.class.getCanonicalName()
                    .equals(event.getAttributes().get(AttributeKey.stringKey("exception.type")))),
            "the server span must record the controller's exception; events were " + server.getEvents());
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void shouldExposeControllerTraceIdAfterChain() {
        String traceId = IdGenerator.random().generateTraceId();

        restTestClient.get().uri("/items/42")
            .header("traceparent", traceparent(traceId, IdGenerator.random().generateSpanId(), NOT_SAMPLED))
            .exchange()
            .expectStatus().isOk();

        await().atMost(RECORDING_DEADLINE).until(() -> controllerLog.getRecordedMdcs().stream()
            .anyMatch(recorded -> traceId.equals(recorded.get("traceId"))));
        await().atMost(RECORDING_DEADLINE).untilAsserted(() ->
            assertTrue(spanRecording.getTraceIdsAfterChain().contains(traceId),
                "the observation context read after the chain must carry the trace id " + traceId
                    + " the controller logged under"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldCarryServerTraceIdInAccessLine() {
        String traceId = IdGenerator.random().generateTraceId();

        restTestClient.get().uri("/items/42")
            .header("traceparent", traceparent(traceId, IdGenerator.random().generateSpanId(), NOT_SAMPLED))
            .exchange()
            .expectStatus().isOk();

        await().atMost(RECORDING_DEADLINE).untilAsserted(() ->
            assertTrue(accessLog.getRecordedKeyValues().stream()
                    .anyMatch(pairs -> traceId.equals(pairs.get("trace_id")) && "/items/42".equals(pairs.get("path"))),
                "the access line must carry the trace id of the observation that served the request; lines were "
                    + accessLog.getRecordedKeyValues()));
    }

    private List<SpanData> awaitServerSpans(String traceId) {
        // requestDestroyed fires after the error-page dispatch, so every span the chain ended is exported by then.
        await().atMost(RECORDING_DEADLINE).until(() -> spanRecording.getTraceIdsAfterChain().contains(traceId));
        return spansOf(traceId).stream().filter(span -> span.getKind() == SpanKind.SERVER).toList();
    }

    private List<SpanData> spansOf(String traceId) {
        return spanRecording.getFinishedSpans().stream().filter(span -> span.getTraceId().equals(traceId)).toList();
    }

    private static boolean descendsFrom(String spanId, String ancestorId, List<SpanData> trace) {
        Map<String, String> parentIds = trace.stream()
            .collect(Collectors.toMap(SpanData::getSpanId, SpanData::getParentSpanId));
        for (String id = spanId; parentIds.containsKey(id); id = parentIds.get(id)) {
            if (id.equals(ancestorId)) {
                return true;
            }
        }
        return false;
    }

    private static Logger accessLogger() {
        return (Logger) LoggerFactory.getLogger(HttpAccessLogHandler.class);
    }

    private static Logger controllerLogger() {
        return (Logger) LoggerFactory.getLogger(ItemController.class);
    }

    private static String traceparent(String traceId, String parentSpanId, String flags) {
        return "00-" + traceId + "-" + parentSpanId + "-" + flags;
    }
}

package io.github.azholdaspaev.nettyloomspring.autoconfigure.streaming;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.streaming.app.StreamingController;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.streaming.app.StreamingGate;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.streaming.app.StreamingTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpClient;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming is a property of the wire, not of the body a client ends up with: an implementation that
 * buffered everything and sent it at the end would satisfy any assertion made on the finished response.
 * So these speak HTTP over a raw socket, the way {@code HttpKeepAliveIntegrationTest} does, and read the
 * response one chunk at a time.
 */
@SpringBootTest(
    classes = StreamingTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class StreamingIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    StreamingGate gate;

    /** Each event is produced only after the previous one has been read off the socket. */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shouldDeliverEachEventBeforeTheNextIsProduced() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "GET /streaming/events HTTP/1.1", "Host: localhost");

            gate.release();
            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
            assertTrue(response.isChunked(), "a body of unknown length must be chunked");
            assertNull(response.header(HttpHeaderNames.CONTENT_LENGTH),
                "a length cannot be declared for a body that is still being produced");

            assertEquals("data: event 1\n\n", response.readChunk());
            gate.release();
            assertEquals("data: event 2\n\n", response.readChunk());
            gate.release();
            assertEquals("data: event 3\n\n", response.readChunk());
            assertEquals(RawHttpResponse.TERMINATOR, response.readChunk(), "the stream must be terminated");
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shouldStreamABodyLargerThanTheAggregatorLimit() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "GET /streaming/large HTTP/1.1", "Host: localhost");
            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());

            assertTrue(response.isChunked());
            String first = response.readChunk();
            assertTrue(first.length() < StreamingController.LARGE_BODY_BYTES,
                "the body must span several chunks, or this asserts nothing about streaming");
            assertEquals(StreamingController.LARGE_BODY_BYTES, first.length() + response.countBody());

            RawHttpClient.send(socket, "GET /streaming/sized HTTP/1.1", "Host: localhost");
            assertEquals(200, RawHttpResponse.read(socket.getInputStream()).status(),
                "the connection must survive a streamed response and serve the next request");
        }
    }

    /**
     * A handler that declares a length streams unframed rather than chunked — the shape
     * {@code /actuator/heapdump} has, since its converter knows the resource's size up front.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shouldStreamWithContentLengthWhenTheHandlerDeclaresOne() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "GET /streaming/sized HTTP/1.1", "Host: localhost");
            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());

            assertEquals(String.valueOf(StreamingController.SIZED_BODY.length()),
                response.header(HttpHeaderNames.CONTENT_LENGTH));
            assertNull(response.header(HttpHeaderNames.TRANSFER_ENCODING),
                "a declared length already delimits the body");
            assertEquals(StreamingController.SIZED_BODY, response.readBody());
        }
    }

    /** Netty's codec drops the body of a HEAD, terminator included, so the framing must stand alone. */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shouldAnswerAHeadRequestForAStreamedBodyWithoutOne() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "HEAD /streaming/large HTTP/1.1", "Host: localhost");
            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());

            assertEquals(200, response.status());

            RawHttpClient.send(socket, "GET /streaming/sized HTTP/1.1", "Host: localhost");
            assertEquals(200, RawHttpResponse.read(socket.getInputStream()).status(),
                "no body may have been written, or the next response would read as part of this one");
        }
    }

    /**
     * Spring's {@code HttpEntityMethodProcessor} flushes the response after writing an entity, and a
     * flush now genuinely commits — so these lose their {@code Content-Length}. That is Tomcat's
     * behaviour too, the converter having no length to declare before it serializes.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shouldChunkAResponseEntityBody() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "GET /streaming/entity HTTP/1.1", "Host: localhost");
            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());

            assertTrue(response.isChunked());
            assertEquals("{\"text\":\"returned as an entity\"}", response.readBody());
        }
    }

    private Socket connect() throws IOException {
        return RawHttpClient.connect(port, Duration.ofSeconds(10));
    }
}

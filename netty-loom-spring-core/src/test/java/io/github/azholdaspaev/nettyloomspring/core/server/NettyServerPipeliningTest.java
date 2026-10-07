package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.core.support.HttpWireClient;
import io.github.azholdaspaev.nettyloomspring.core.support.NettyServerFixture;
import io.netty.buffer.Unpooled;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Response ordering (issue #63), and response framing after an interim 100 (issue #199), on a
 * pipelined connection. Real socket on purpose: what is under test is the bytes on the wire, which
 * only exist there.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class NettyServerPipeliningTest {

    /**
     * How long the first request yields to the second before giving up on being overtaken.
     */
    private static final Duration OVERTAKE_WINDOW = Duration.ofSeconds(1);
    private static final String HEAD_REQUEST = "HEAD /next HTTP/1.1\r\nHost: localhost\r\n\r\n";
    private static final String CONNECT_REQUEST = "CONNECT localhost:443 HTTP/1.1\r\nHost: localhost:443\r\n\r\n";
    private static final List<String> CHUNKED_BODY = List.of("8", "/chunked", "0", "");

    private final CountDownLatch secondResponded = new CountDownLatch(1);

    private NettyServer nettyServer;
    private ExecutorService dispatchExecutor;

    @BeforeEach
    void setUp() {
        dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor();
        nettyServer = newServer();
        nettyServer.start();
    }

    @AfterEach
    void tearDown() {
        secondResponded.countDown();
        if (nettyServer.isRunning()) {
            nettyServer.shutdown(Duration.ZERO);
        }
        dispatchExecutor.shutdownNow();
    }

    @Test
    void shouldAnswerPipelinedRequestsInRequestOrder() throws Exception {
        try (HttpWireClient client = HttpWireClient.connect(nettyServer.getPort())) {
            // One write, so both requests land in one TCP segment and are decoded in one turn.
            client.send("GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n"
                + "GET /second HTTP/1.1\r\nHost: localhost\r\n\r\n");

            assertEquals("/first", client.readResponseBody(),
                "the first response on the wire is the answer to the first request, however long it took");
            assertEquals("/second", client.readResponseBody(),
                "the second response must follow the first, not overtake it");
        }
    }

    @Test
    void shouldSequenceInterimResponseBehindEarlierPipelinedResponse() throws Exception {
        // issue #78
        try (HttpWireClient client = HttpWireClient.connect(nettyServer.getPort())) {
            client.send("GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n"
                + "POST /second HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Length: 5\r\nExpect: 100-continue\r\n\r\n");

            assertEquals("/first", client.readResponseBody(),
                "the invitation to send the second body must not overtake the answer to the first");

            assertEquals("HTTP/1.1 100 Continue", client.readHeaderBlock().getFirst(),
                "the invitation must still be sent, once the exchange before it is done");
            client.send("hello");
            assertEquals("/second", client.readResponseBody());
        }
    }

    @Test
    void shouldPreservePostBodyAfterContinueBeforePipelinedHead() throws Exception {
        // issue #199
        try (HttpWireClient client = HttpWireClient.connect(nettyServer.getPort())) {
            sendContinuedPostThen(client, "/buffered", HEAD_REQUEST);

            assertEquals("/buffered", client.readResponseBody(),
                "the POST response declares Content-Length, so a missing body stalls the client");
        }
    }

    @Test
    void shouldPreserveChunkedBodyAfterContinueBeforePipelinedHead() throws Exception {
        try (HttpWireClient client = HttpWireClient.connect(nettyServer.getPort())) {
            sendContinuedPostThen(client, "/chunked", HEAD_REQUEST);

            client.readHeaderBlock();
            assertChunkedBody(client, "a chunked POST response without its chunks and terminator never ends");
        }
    }

    @Test
    void shouldKeepChunkedHeaderAfterContinueBeforePipelinedConnect() throws Exception {
        try (HttpWireClient client = HttpWireClient.connect(nettyServer.getPort())) {
            sendContinuedPostThen(client, "/chunked", CONNECT_REQUEST);

            List<String> headerBlock = client.readHeaderBlock();
            assertTrue(headerBlock.contains(HttpHeaderNames.TRANSFER_ENCODING + ": " + HttpHeaderValues.CHUNKED),
                "chunk framing without its header is read as the body itself: " + headerBlock);
            assertChunkedBody(client, "the chunks must follow the header block");
        }
    }

    /**
     * The first request yields to the second, so with nothing sequencing the writes the second
     * response wins deterministically rather than by a sleep race.
     */
    private HttpRequestDispatcher overtakingDispatcher() {
        return (request, body, _, writer) -> {
            // Answering only after the whole body lets a request pipelined behind it be decoded first.
            body.readAllBytes();
            if ("/first".equals(request.uri())) {
                secondResponded.await(OVERTAKE_WINDOW.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                secondResponded.countDown();
            }
            if ("/chunked".equals(request.uri())) {
                writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
                writer.write(new DefaultHttpContent(Unpooled.copiedBuffer(request.uri(), StandardCharsets.US_ASCII)));
                writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
            } else {
                writer.write(textResponse(request.uri()));
            }
        };
    }

    private NettyServer newServer() {
        return NettyServerFixture.newHttpServer(
            new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)),
            overtakingDispatcher(), dispatchExecutor);
    }

    private static void sendContinuedPostThen(HttpWireClient client, String uri, String nextRequest)
        throws Exception {
        client.send("POST " + uri + " HTTP/1.1\r\nHost: localhost\r\n"
            + "Content-Length: 5\r\nExpect: 100-continue\r\n\r\n");
        assertEquals("HTTP/1.1 100 Continue", client.readHeaderBlock().getFirst());
        // One write, so the pipelined request is decoded in the same turn as the end of the body.
        client.send("hello" + nextRequest);
    }

    private static void assertChunkedBody(HttpWireClient client, String message) throws Exception {
        for (String line : CHUNKED_BODY) {
            assertEquals(line, client.readLine(), message);
        }
    }

    private static FullHttpResponse textResponse(String body) {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.copiedBuffer(body, StandardCharsets.US_ASCII));
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        return response;
    }
}

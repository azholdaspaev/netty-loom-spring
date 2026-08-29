package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpDrainHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpPipeliningHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestBodyLimitHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestHandler;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NamedChannelHandler;
import io.github.azholdaspaev.nettyloomspring.core.support.NettyServerFixture;
import io.netty.buffer.Unpooled;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Response ordering on a pipelined connection (issue #63). Real socket on purpose: what is under
 * test is the byte order on the wire, which only exists there.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class NettyServerPipeliningTest {

    private static final int MAX_HTTP_REQUEST_BODY_BYTES = 64 * 1024;

    /**
     * How long the first request yields to the second before giving up on being overtaken.
     */
    private static final Duration OVERTAKE_WINDOW = Duration.ofSeconds(1);

    private static final Duration UNREACHED_WRITE_STALL_TIMEOUT = Duration.ofSeconds(60);

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
        try (Socket client = connect()) {
            // One write, so both requests land in one TCP segment and are decoded in one turn.
            send(client, "GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n"
                + "GET /second HTTP/1.1\r\nHost: localhost\r\n\r\n");

            BufferedReader reader = reader(client);
            assertEquals("/first", readResponseBody(reader),
                "the first response on the wire is the answer to the first request, however long it took");
            assertEquals("/second", readResponseBody(reader),
                "the second response must follow the first, not overtake it");
        }
    }

    @Test
    void shouldSequenceAnInterimResponseBehindAnEarlierPipelinedResponse() throws Exception {
        // issue #78
        try (Socket client = connect()) {
            send(client, "GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n"
                + "POST /second HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Length: 5\r\nExpect: 100-continue\r\n\r\n");

            BufferedReader reader = reader(client);
            assertEquals("/first", readResponseBody(reader),
                "the invitation to send the second body must not overtake the answer to the first");

            assertEquals("HTTP/1.1 100 Continue", readHeaderBlock(reader).getFirst(),
                "the invitation must still be sent, once the exchange before it is done");
            send(client, "hello");
            assertEquals("/second", readResponseBody(reader));
        }
    }

    /**
     * The first request yields to the second, so with nothing sequencing the writes the second
     * response wins deterministically rather than by a sleep race.
     */
    private HttpRequestDispatcher overtakingDispatcher() {
        return (request, _, _, writer) -> {
            if ("/first".equals(request.uri())) {
                secondResponded.await(OVERTAKE_WINDOW.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                secondResponded.countDown();
            }
            writer.write(textResponse(request.uri()));
        };
    }

    private NettyServer newServer() {
        HttpConnectionRegistry connectionRegistry = new HttpConnectionRegistry(
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
        NettyServerConfiguration configuration = new NettyServerConfiguration(
            0, InetAddress.getLoopbackAddress(), 0, 0, false);
        return NettyServerFixture.newServer(configuration, connectionRegistry, List.of(
            new NamedChannelHandler("httpCodec", HttpServerCodec::new),
            new NamedChannelHandler("httpKeepAlive", HttpServerKeepAliveHandler::new),
            new NamedChannelHandler("drain", () -> new HttpDrainHandler(connectionRegistry)),
            new NamedChannelHandler("pipelining", HttpPipeliningHandler::new),
            new NamedChannelHandler("bodyLimit", () -> new HttpRequestBodyLimitHandler(MAX_HTTP_REQUEST_BODY_BYTES)),
            new NamedChannelHandler("dispatcher",
                () -> new HttpRequestHandler(overtakingDispatcher(), dispatchExecutor, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT))));
    }

    private static FullHttpResponse textResponse(String body) {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.copiedBuffer(body, StandardCharsets.US_ASCII));
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        return response;
    }

    private Socket connect() throws IOException {
        Socket client = new Socket();
        client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), nettyServer.getPort()), 1_000);
        client.setSoTimeout(10_000);
        return client;
    }

    private static void send(Socket client, String request) throws IOException {
        client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
    }

    /**
     * One reader per socket: a fresh one would discard whatever the previous had already buffered.
     */
    private static BufferedReader reader(Socket client) throws IOException {
        return new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
    }

    /**
     * Header block, then exactly Content-Length bytes — US-ASCII, so one char is one byte.
     */
    private static String readResponseBody(BufferedReader reader) throws IOException {
        int contentLength = contentLength(readHeaderBlock(reader));
        char[] body = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int count = reader.read(body, read, contentLength - read);
            if (count < 0) {
                throw new EOFException("connection closed after " + read + " of " + contentLength + " body bytes");
            }
            read += count;
        }
        return new String(body);
    }

    private static List<String> readHeaderBlock(BufferedReader reader) throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    private static int contentLength(List<String> headerBlock) {
        return headerBlock.stream()
            .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
            .map(line -> line.substring(line.indexOf(':') + 1).trim())
            .mapToInt(Integer::parseInt)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no Content-Length in " + headerBlock));
    }
}

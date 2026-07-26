package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpDrainHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestHandler;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.DefaultNettyPipelineConfigurer;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NamedChannelHandler;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineConfigurer;
import io.netty.buffer.Unpooled;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
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
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graceful shutdown drains in-flight <em>requests</em>, not open <em>sockets</em> (issue #67).
 *
 * <p>These run a real HTTP pipeline over a real socket on purpose: what is under test is when the
 * connection closes and what the final response says, and both only exist on the wire.
 * {@link NettyServerTest} covers the socket-level cases against an empty pipeline.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class NettyServerDrainTest {

    private static final int MAX_HTTP_REQUEST_BODY_BYTES = 64 * 1024;

    private final CountDownLatch dispatcherEntered = new CountDownLatch(1);
    private final CountDownLatch releaseDispatcher = new CountDownLatch(1);

    private NettyServer nettyServer;
    private ExecutorService dispatchExecutor;
    private ExecutorService shutdownExecutor;

    @BeforeEach
    void setUp() {
        dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor();
        shutdownExecutor = Executors.newSingleThreadExecutor();
        nettyServer = newServer();
        nettyServer.start();
    }

    @AfterEach
    void tearDown() {
        releaseDispatcher.countDown();
        if (nettyServer.isRunning()) {
            nettyServer.shutdown(Duration.ZERO);
        }
        dispatchExecutor.shutdownNow();
        shutdownExecutor.shutdownNow();
    }

    @Test
    void shouldAnswerAnInFlightRequestBeforeCompletingShutdown() throws Exception {
        try (Socket client = connect()) {
            send(client, "GET /slow HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertTrue(dispatcherEntered.await(5, TimeUnit.SECONDS), "request must have reached the dispatcher");

            Future<NettyShutdownResult> shutdown = shutdownExecutor.submit(
                () -> nettyServer.shutdown(Duration.ofSeconds(10)));
            assertThrows(TimeoutException.class, () -> shutdown.get(300, TimeUnit.MILLISECONDS),
                "shutdown must keep waiting while a request is still being served");

            releaseDispatcher.countDown();

            assertEquals("HTTP/1.1 200 OK", readStatusLine(client),
                "a request in flight when shutdown began must still receive its response");
            assertEquals(NettyShutdownResult.IDLE, shutdown.get(5, TimeUnit.SECONDS),
                "shutdown completes as soon as the last in-flight request is answered");
        }
    }

    /**
     * A request is in flight from the moment its first bytes land, not from the moment the aggregator
     * finishes assembling it. Any body split across TCP segments — an upload, a slow client, a
     * 100-continue flow — leaves the connection mid-request with nothing yet dispatched.
     */
    @Test
    void shouldAnswerARequestWhoseBodyArrivesAfterTheDrainBegins() throws Exception {
        releaseDispatcher.countDown();
        try (Socket client = connect()) {
            send(client, "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\n");
            Thread.sleep(200);

            Future<NettyShutdownResult> shutdown = shutdownExecutor.submit(
                () -> nettyServer.shutdown(Duration.ofSeconds(10)));
            Thread.sleep(200);

            send(client, "hello");

            assertEquals("HTTP/1.1 200 OK", readStatusLine(client),
                "a request already on the wire when the drain began must still be answered");
            assertEquals(NettyShutdownResult.IDLE, shutdown.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldReportRequestsActiveWhenARequestOutlastsTheGracePeriod() throws Exception {
        try (Socket client = connect()) {
            send(client, "GET /slow HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertTrue(dispatcherEntered.await(5, TimeUnit.SECONDS), "request must have reached the dispatcher");

            NettyShutdownResult result = nettyServer.shutdown(Duration.ofMillis(500));

            assertEquals(NettyShutdownResult.REQUESTS_ACTIVE, result,
                "a request still running at the deadline must be reported, not passed off as idle");
        }
    }

    private NettyServer newServer() {
        HttpConnectionRegistry connectionRegistry = new HttpConnectionRegistry(
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
        NettyPipelineConfigurer pipelineConfigurer = new DefaultNettyPipelineConfigurer(List.of(
            new NamedChannelHandler("httpCodec", HttpServerCodec::new),
            new NamedChannelHandler("httpKeepAlive", HttpServerKeepAliveHandler::new),
            new NamedChannelHandler("drain", () -> new HttpDrainHandler(connectionRegistry)),
            new NamedChannelHandler("aggregator", () -> new HttpObjectAggregator(MAX_HTTP_REQUEST_BODY_BYTES)),
            new NamedChannelHandler("dispatcher",
                () -> new HttpRequestHandler(blockingDispatcher(), dispatchExecutor))));
        NettyServerConfiguration configuration = new NettyServerConfiguration(
            0, InetAddress.getLoopbackAddress(), 0, 0, false);
        return new NettyServer(configuration,
            new NettyServerChannelInitializer(pipelineConfigurer, connectionRegistry),
            new NettyIoHandlerFactory("auto"),
            connectionRegistry);
    }

    /** Holds the request open until the test releases it, so shutdown is guaranteed to race it. */
    private HttpRequestDispatcher blockingDispatcher() {
        return (_, _) -> {
            dispatcherEntered.countDown();
            if (!releaseDispatcher.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("dispatcher was never released");
            }
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            return response;
        };
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

    private static String readStatusLine(Socket client) throws IOException {
        return new BufferedReader(
            new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII)).readLine();
    }
}

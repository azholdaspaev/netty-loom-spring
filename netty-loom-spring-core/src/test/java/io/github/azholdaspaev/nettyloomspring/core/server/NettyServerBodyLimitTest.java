package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.core.support.HttpWireClient;
import io.github.azholdaspaev.nettyloomspring.core.support.NettyServerFixture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * How a refused body ends on the wire (issue #200). Real socket on purpose: the reset that loses the
 * {@code 413} comes from unread bytes in the kernel's receive queue, which {@code EmbeddedChannel}
 * does not have.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class NettyServerBodyLimitTest {

    private static final String REFUSED = "HTTP/1.1 413 Request Entity Too Large";

    private static final String PIECE = "x".repeat(64 * 1024);

    /** Past what loopback send and receive buffers absorb, so the server must read it for the write to finish. */
    private static final int UNBUFFERED_BODY_BYTES = 8 * 1024 * 1024;

    private static final int REFUSED_BODY_BYTES = 2 * 1024 * 1024;

    private static final Duration SHORT_SWALLOW_TIMEOUT = Duration.ofMillis(200);

    private NettyServer nettyServer;
    private ExecutorService dispatchExecutor;

    @BeforeEach
    void setUp() {
        dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (nettyServer != null && nettyServer.isRunning()) {
            nettyServer.shutdown(Duration.ZERO);
        }
        dispatchExecutor.shutdownNow();
    }

    @Test
    void shouldHalfCloseAfterRefusalWhileBodyStillArrives() throws Exception {
        startServer();
        try (HttpWireClient client = HttpWireClient.connect(nettyServer.getPort())) {
            client.send(head(UNBUFFERED_BODY_BYTES) + PIECE);

            assertEquals(REFUSED, client.readHeaderBlock().getFirst());
            assertNull(client.readLine(), "the refusal is the last thing the server sends, so its end is the stream's");

            for (int piece = 0; piece < 4; piece++) {
                client.send(PIECE);
            }
        }
    }

    @Test
    void shouldDeliver413ToClientThatSendsRefusedBodyInFull() throws Exception {
        startServer();
        try (HttpWireClient client = HttpWireClient.connect(nettyServer.getPort())) {
            client.send(head(UNBUFFERED_BODY_BYTES));
            for (int sent = 0; sent < UNBUFFERED_BODY_BYTES; sent += PIECE.length()) {
                client.send(PIECE);
            }

            assertEquals(REFUSED, client.readHeaderBlock().getFirst(),
                "a client that writes its whole body before reading must still find the refusal");
            assertNull(client.readLine());
        }
    }

    @Test
    void shouldDeliver413ToClientPipeliningBehindRefusedBody() throws Exception {
        startServer();
        try (HttpWireClient client = HttpWireClient.connect(nettyServer.getPort())) {
            client.send(head(REFUSED_BODY_BYTES));
            for (int sent = 0; sent < REFUSED_BODY_BYTES; sent += PIECE.length()) {
                client.send(PIECE);
            }
            client.send(head(UNBUFFERED_BODY_BYTES));
            for (int sent = 0; sent < UNBUFFERED_BODY_BYTES; sent += PIECE.length()) {
                client.send(PIECE);
            }

            assertEquals(REFUSED, client.readHeaderBlock().getFirst(),
                "the refused body's end is not the client's: closing there resets over the request behind it");
            assertNull(client.readLine());
        }
    }

    @Test
    void shouldDeliver413WhenTwoRequestsArePipelinedBehindRefusal() throws Exception {
        nettyServer = NettyServerFixture.newHttpServer(newRegistry(), unreachedDispatcher(), dispatchExecutor,
            Long.MAX_VALUE, Duration.ofSeconds(3));
        nettyServer.start();
        try (HttpWireClient client = HttpWireClient.connect(nettyServer.getPort())) {
            client.send(head(REFUSED_BODY_BYTES));
            for (int sent = 0; sent < REFUSED_BODY_BYTES; sent += PIECE.length()) {
                client.send(PIECE);
            }
            client.send("GET /behind HTTP/1.1\r\nHost: localhost\r\n\r\n");
            client.send(head(UNBUFFERED_BODY_BYTES));
            for (int sent = 0; sent < UNBUFFERED_BODY_BYTES; sent += PIECE.length()) {
                client.send(PIECE);
            }

            assertEquals(REFUSED, client.readHeaderBlock().getFirst(),
                "a request pipelined behind the refusal must not stop the drain reading what follows it");
            assertNull(client.readLine());
        }
    }

    @Test
    void shouldCloseOnceSwallowTimeoutElapses() throws Exception {
        nettyServer = NettyServerFixture.newHttpServer(newRegistry(), unreachedDispatcher(), dispatchExecutor,
            Long.MAX_VALUE, SHORT_SWALLOW_TIMEOUT);
        nettyServer.start();
        try (HttpWireClient client = HttpWireClient.connect(nettyServer.getPort())) {
            client.send(head(Integer.MAX_VALUE));
            assertEquals(REFUSED, client.readHeaderBlock().getFirst());
            assertNull(client.readLine());

            /*
             * One byte at a time, so the server never has a backlog to reset over: the first write it
             * refuses is the first one sent after the timer closed the socket.
             */
            assertThrows(IOException.class, () -> {
                while (true) {
                    client.send("x");
                    Thread.sleep(SHORT_SWALLOW_TIMEOUT.dividedBy(10));
                }
            }, "a body that neither ends nor grows must still not hold the connection past the timeout");
        }
    }

    private void startServer() {
        nettyServer = NettyServerFixture.newHttpServer(newRegistry(), unreachedDispatcher(), dispatchExecutor);
        nettyServer.start();
    }

    private static HttpConnectionRegistry newRegistry() {
        return new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
    }

    private static String head(int contentLength) {
        return "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + contentLength + "\r\n\r\n";
    }

    private static HttpRequestDispatcher unreachedDispatcher() {
        return (_, _, _, _) -> fail("a refused request must not be dispatched");
    }
}

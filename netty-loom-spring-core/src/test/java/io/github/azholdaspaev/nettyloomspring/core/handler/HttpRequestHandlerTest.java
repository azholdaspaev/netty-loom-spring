package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.github.azholdaspaev.nettyloomspring.core.support.SpinWait;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestHandlerTest {

    private static final Executor DIRECT = Runnable::run;

    /** Generous: it bounds a spin on another thread's progress, not the progress itself. */
    private static final Duration PARK_LIMIT = Duration.ofSeconds(5);

    private final HttpConnectionRegistry connectionRegistry =
        new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));

    @Test
    void shouldWriteDispatcherResponseToChannel() {
        FullHttpResponse canned = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.EMPTY_BUFFER);
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler((_, _, writer) -> writer.write(canned), DIRECT, connectionRegistry));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertSame(canned, out, "handler must forward the dispatcher's response unchanged");
        out.release();
        channel.finish();
    }

    /**
     * A response written in parts must reach the wire as those parts, in order — that is what the
     * exchange-tracking handlers below this one are keyed to see.
     */
    @Test
    void shouldWriteAStreamedResponseAsHeadChunksAndLastContent() {
        HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, writer) -> {
                writer.write(head);
                writer.write(new DefaultHttpContent(Unpooled.copiedBuffer("one", StandardCharsets.UTF_8)));
                writer.write(new DefaultHttpContent(Unpooled.copiedBuffer("two", StandardCharsets.UTF_8)));
                writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
            },
            DIRECT, connectionRegistry));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        assertSame(head, channel.readOutbound(), "the head must reach the wire first");
        assertEquals("one", readChunk(channel));
        assertEquals("two", readChunk(channel));
        assertSame(LastHttpContent.EMPTY_LAST_CONTENT, channel.readOutbound(),
            "the stream must be terminated by a LastHttpContent");
        assertNull(channel.readOutbound(), "nothing may follow the end of the response");
        channel.finish();
    }

    @Test
    void shouldFrameAnUnframedStreamingHeadAsChunkedForHttp11() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, writer) -> {
                writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
                writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
            },
            DIRECT, connectionRegistry));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        HttpResponse head = channel.readOutbound();
        assertTrue(HttpUtil.isTransferEncodingChunked(head),
            "a streaming response that declares no length must be framed as chunked");
        channel.finish();
    }

    @Test
    void shouldNotReframeAHeadThatAlreadySetContentLength() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, writer) -> {
                HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                head.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 3);
                writer.write(head);
                writer.write(new DefaultHttpContent(Unpooled.copiedBuffer("abc", StandardCharsets.UTF_8)));
                writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
            },
            DIRECT, connectionRegistry));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        HttpResponse head = channel.readOutbound();
        assertFalse(HttpUtil.isTransferEncodingChunked(head),
            "a declared length already delimits the body; chunked on top of it would mis-frame the response");
        assertEquals(3, HttpUtil.getContentLength(head, -1L));
        readChunk(channel);
        channel.finish();
    }

    /**
     * An HTTP/1.0 client reads chunk-size lines as body content, so the head is left unframed and the
     * close delimits it. Netty's own keep-alive handler is what turns that into {@code Connection: close}
     * — this asserts the outcome rather than adding HTTP/1.0 logic of our own.
     */
    @Test
    void shouldLeaveAnHttp10StreamingHeadUnframedSoTheConnectionDelimitsIt() {
        EmbeddedChannel channel = keepAliveChannel((_, _, writer) -> {
            writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
            writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
        });
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        channel.writeInbound(request);
        channel.runPendingTasks();

        HttpResponse head = channel.readOutbound();
        assertFalse(HttpUtil.isTransferEncodingChunked(head),
            "an HTTP/1.0 client cannot decode chunked framing");
        assertEquals(HttpHeaderValues.CLOSE.toString(), head.headers().get(HttpHeaderNames.CONNECTION),
            "an unframed body is delimited by the close, so the keep-alive echo must be overruled");
        assertFalse(channel.isOpen(), "the connection must close to mark the end of the body");
        channel.finish();
    }

    /**
     * The servlet contract is that a write to a departed client fails, and a streaming handler only
     * learns of the departure by being told. The part it handed over is the writer's to free.
     *
     * <p>The type is load-bearing: {@link HttpExceptionHandler} classifies a departed client by it, so
     * anything else there is reported as a server fault the application never committed.
     */
    @Test
    void shouldReportAGoneClientAsADisconnectAndReleaseTheChunk() throws Exception {
        ByteBuf orphan = Unpooled.copiedBuffer("gone", StandardCharsets.UTF_8);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler(
                (_, _, writer) -> {
                    connectionClosed.await();
                    try {
                        writer.write(new DefaultHttpContent(orphan));
                    } catch (Throwable caught) {
                        failure.set(caught);
                    }
                },
                task -> worker.set(startQuietly(task)), connectionRegistry),
            capture);

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.close();
        connectionClosed.countDown();
        worker.get().join();

        assertInstanceOf(ClosedChannelException.class, failure.get(),
            "a write to a gone client must be reported as a disconnect the tail handler recognises");
        assertEquals(0, orphan.refCnt(), "the writer owns the part it could not send");
        assertNull(capture.captured,
            "a client that left is why the response stopped, not a dispatcher that broke its contract");
    }

    /**
     * Backpressure: a handler producing faster than the socket drains must be made to wait, or the
     * chunks it is not queueing in heap simply queue in {@code ChannelOutboundBuffer} instead and the
     * streaming path saves nothing.
     *
     * <p>On a real loop rather than an {@link EmbeddedChannel}, for the reason
     * {@link #shouldReportAnAbandonedDispatchItselfWhenTheEventLoopHasTerminated} gives: that channel's
     * loop reports {@code inEventLoop()} unconditionally true, so a wait inside the writer is refused as
     * a deadlock instead of parking.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldParkTheDispatchThreadWhileTheConnectionIsUnwritable() throws Exception {
        CountDownLatch responseFinished = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        try (StalledConnection connection = new StalledConnection(new HttpRequestHandler(
            (_, _, writer) -> {
                writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
                writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
                responseFinished.countDown();
            },
            task -> worker.set(startQuietly(task)), connectionRegistry))) {

            connection.dispatch();

            SpinWait.untilParked(worker::get, PARK_LIMIT,
                "the dispatch thread must wait rather than queue past an unwritable connection");
            assertEquals(1, responseFinished.getCount(),
                "a thread parked inside write() cannot have finished the response");

            connection.drain();
            SpinWait.until(() -> responseFinished.getCount() == 0, PARK_LIMIT,
                "the dispatch must resume once the connection has taken what it was given");
        }
    }

    /**
     * The wait has to be bounded. {@link HttpReadTimeoutHandler} suspends its clock until the
     * {@code LastHttpContent} is written, so a peer that stops reading mid-stream would otherwise park
     * the dispatch thread for ever <em>and</em> leave the connection with nothing left to reclaim it —
     * the failure that handler's javadoc forbids of everything below it.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldGiveUpOnAConnectionThatStaysUnwritable() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (StalledConnection connection = new StalledConnection(new HttpRequestHandler(
            (_, _, writer) -> {
                try {
                    writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
                } catch (Throwable caught) {
                    failure.set(caught);
                }
            },
            task -> worker.set(startQuietly(task)), connectionRegistry, Duration.ofMillis(100)))) {

            connection.dispatch();
            SpinWait.until(() -> worker.get() != null, PARK_LIMIT, "the dispatch must have been submitted");
            worker.get().join();

            assertInstanceOf(IOException.class, failure.get(),
                "a connection that never drains must be reported to the handler, not waited on for ever");
            assertFalse(connection.channel.isOpen(),
                "the connection must be given up on, so nothing else has to reclaim it");
        }
    }

    /**
     * Once the head is on the wire the status is spent, and the tail handler's error response would be
     * read by the client as more body. Closing is the only honest way left to say the response is bad.
     */
    @Test
    void shouldCloseInsteadOfFiringExceptionCaughtWhenTheDispatcherFailsAfterCommitting() {
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler(
                (_, _, writer) -> {
                    writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
                    throw new IllegalStateException("halfway through the body");
                },
                DIRECT, connectionRegistry),
            capture);

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        assertNotNull(channel.readOutbound(), "the head that was already written stands");
        assertNull(channel.readOutbound(), "a second response would be read as the body of the first");
        assertNull(capture.captured,
            "the tail handler must not be asked for an error response there is no longer room for");
        assertFalse(channel.isOpen(), "the truncated response must be marked by closing the connection");
    }

    @Test
    void shouldFailAnExchangeTheDispatcherLeftUnanswered() {
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler((_, _, _) -> { }, DIRECT, connectionRegistry),
            capture);

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        assertInstanceOf(IllegalStateException.class, capture.captured,
            "a dispatcher that writes nothing leaves the exchange hanging, so it must be reported");
        channel.finish();
    }

    @Test
    void shouldCloseAnExchangeTheDispatcherLeftHalfWritten() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, writer) -> writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)),
            DIRECT, connectionRegistry));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        assertFalse(channel.isOpen(),
            "a body the client is still waiting for must be ended by the close, not left open");
    }

    /**
     * A connected channel on a real event loop, held permanently unwritable, whose socket takes every
     * write without ever settling it — a peer whose receive window stays at zero.
     */
    private static final class StalledConnection extends ChannelOutboundHandlerAdapter implements AutoCloseable {

        private static final AtomicInteger ADDRESSES = new AtomicInteger();

        private final List<ChannelPromise> unsettled = new CopyOnWriteArrayList<>();
        private final MultiThreadIoEventLoopGroup group =
            new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        private final Channel listener;
        private final Channel channel;

        StalledConnection(HttpRequestHandler handler) throws Exception {
            LocalAddress address = new LocalAddress(getClass().getName() + "-" + ADDRESSES.incrementAndGet());
            listener = new ServerBootstrap().group(group).channel(LocalServerChannel.class)
                .childHandler(new ChannelInboundHandlerAdapter())
                .bind(address).sync().channel();
            channel = new Bootstrap().group(group).channel(LocalChannel.class)
                .handler(this)
                .connect(address).sync().channel();
            channel.pipeline().addLast(handler);
            // On the loop, which is where writability is owned. A user-defined flag flips isWritable()
            // without having to fill the outbound buffer to a water mark first.
            channel.eventLoop().submit(
                () -> channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false)).sync();
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            unsettled.add(promise);
        }

        void dispatch() {
            channel.pipeline().fireChannelRead(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        }

        void drain() throws Exception {
            channel.eventLoop().submit(() -> {
                unsettled.forEach(ChannelPromise::trySuccess);
                unsettled.clear();
                channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            }).sync();
        }

        @Override
        public void close() {
            channel.close();
            listener.close();
            group.shutdownGracefully(0, 0, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * A client that hangs up mid-download is the ordinary end of a stream, not a fault. Asserted on the
     * log because that is the whole of the difference: the connection is closed either way.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldNotRecordAClientDisconnectMidResponseAsAFailure() throws Exception {
        CountDownLatch connectionClosed = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, writer) -> {
                writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
                connectionClosed.await();
                writer.write(new DefaultHttpContent(Unpooled.copiedBuffer("more", StandardCharsets.UTF_8)));
            },
            task -> worker.set(startQuietly(task)), connectionRegistry));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/download"));
        SpinWait.until(() -> channel.readOutbound() != null, PARK_LIMIT,
            "the head must reach the wire before the client leaves");
        channel.close();

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream standardError = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            connectionClosed.countDown();
            worker.get().join();
        } finally {
            System.setErr(standardError);
        }

        String logged = captured.toString(StandardCharsets.UTF_8);
        assertFalse(logged.contains("WARN"),
            "a client that hung up mid-stream is not a failure; log was: " + logged);
    }

    /**
     * A status that can never carry a body must carry no framing either. Netty sanitizes some of them
     * for us — {@code HttpResponseEncoder.sanitizeHeadersBeforeEncode} strips {@code Transfer-Encoding}
     * for 1xx and 204 and rewrites 205 — but not 304, while {@code isContentAlwaysEmpty} does swallow
     * 304's body and terminator. So a chunked 304 would go out framed for a body that can never follow.
     * Tomcat suppresses framing for exactly this set in {@code Http11Processor.prepareResponse}.
     */
    @ParameterizedTest
    @ValueSource(ints = {100, 204, 205, 304})
    void shouldLeaveAStatusThatCanNeverCarryABodyUnframed(int status) {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, writer) -> {
                writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status)));
                writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
            },
            DIRECT, connectionRegistry));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        HttpResponse head = channel.readOutbound();
        assertFalse(HttpUtil.isTransferEncodingChunked(head),
            status + " can never carry a body, so it must not be framed for one");
        assertEquals(0, HttpUtil.getContentLength(head, -1L),
            "declared empty so the keep-alive handler can still tell where the message ends");
        channel.finish();
    }

    private static String readChunk(EmbeddedChannel channel) {
        HttpContent chunk = channel.readOutbound();
        String text = chunk.content().toString(StandardCharsets.UTF_8);
        chunk.release();
        return text;
    }

    @Test
    void shouldPassThroughTheIncomingRequestToDispatcher() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher, DIRECT, connectionRegistry));
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, "/submit");

        channel.writeInbound(request);
        channel.runPendingTasks();

        assertSame(request, dispatcher.lastRequest,
            "handler must hand the inbound request to the dispatcher without wrapping");

        FullHttpResponse out = channel.readOutbound();
        out.release();
        channel.finish();
    }

    @Test
    void passesHttpConnectionMetadataToDispatcher() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher, DIRECT, connectionRegistry));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        assertEquals(new HttpConnectionMetadata("", 0, "", 0, false), dispatcher.lastConnection,
            "handler must snapshot the connection metadata and pass it to the dispatcher");

        FullHttpResponse out = channel.readOutbound();
        out.release();
        channel.finish();
    }

    @Test
    void shouldInvokeDispatcherOncePerRequest() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher, DIRECT, connectionRegistry));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/a"));
        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/b"));
        channel.runPendingTasks();

        assertEquals(2, dispatcher.callCount);

        FullHttpResponse first = channel.readOutbound();
        FullHttpResponse second = channel.readOutbound();
        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second, "each dispatch must produce its own response");
        first.release();
        second.release();
        channel.finish();
    }

    @Test
    void shouldPropagateDispatcherExceptionDownPipeline() {
        RuntimeException boom = new RuntimeException("boom");
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler((_, _, _) -> { throw boom; }, DIRECT, connectionRegistry),
            capture);

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        assertSame(boom, capture.captured,
            "exception from dispatcher must propagate via exceptionCaught");
        assertNull(channel.readOutbound(),
            "handler must not write a response when the dispatcher fails");
        channel.finish();
    }

    @Test
    void shouldReleaseRequestAfterDispatch() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher, DIRECT, connectionRegistry));
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

        channel.writeInbound(request);
        channel.runPendingTasks();

        assertEquals(0, request.refCnt(),
            "handler must balance retain()/release() so the request is freed after dispatch");

        FullHttpResponse out = channel.readOutbound();
        assertNotNull(out);
        out.release();
        channel.finish();
    }

    @Test
    void shouldCountADispatchThatIsSubmittedButNotYetRunning() throws InterruptedException {
        CompletableFuture<Runnable> submitted = new CompletableFuture<>();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler((_, _, writer) -> writer.write(emptyOkResponse()), submitted::complete, connectionRegistry));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));

        assertFalse(connectionRegistry.awaitDispatchesFinished(0),
            "a dispatch queued but not yet started must still hold the drain open");

        submitted.join().run();

        assertTrue(connectionRegistry.awaitDispatchesFinished(0),
            "the drain must be released once the queued dispatch has run");
        FullHttpResponse out = channel.readOutbound();
        out.release();
        channel.finish();
    }

    @Test
    void shouldNotLeaveADispatchCountedWhenReleasingTheRequestThrows() throws Exception {
        CountDownLatch pipelineDroppedItsReference = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (request, _, writer) -> {
                pipelineDroppedItsReference.await();
                request.release(request.refCnt());
                writer.write(emptyOkResponse());
            },
            task -> worker.set(startQuietly(task)), connectionRegistry));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        pipelineDroppedItsReference.countDown();
        worker.get().join();

        assertTrue(connectionRegistry.awaitDispatchesFinished(0),
            "a dispatch whose release() threw must still have been counted out");
    }

    @Test
    void shouldNotCountADispatchOutTwiceWhenItsCleanupThrowsOnAnInlineExecutor() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (request, _, writer) -> {
                request.release(request.refCnt());
                writer.write(emptyOkResponse());
            },
            DIRECT, connectionRegistry));

        assertThrows(IllegalReferenceCountException.class, () -> channel.writeInbound(
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")));
        connectionRegistry.dispatchStarted();

        assertFalse(connectionRegistry.awaitDispatchesFinished(0),
            "a live dispatch must still hold the drain open after an earlier task threw");
    }

    @Test
    void shouldContainACleanupFailureThatIsNotAnOverRelease() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, writer) -> writer.write(emptyOkResponse()), DIRECT, connectionRegistry));

        assertThrows(IllegalStateException.class,
            () -> channel.writeInbound(new CleanupFailingRequest()));
        connectionRegistry.dispatchStarted();

        assertFalse(connectionRegistry.awaitDispatchesFinished(0),
            "a live dispatch must still hold the drain open after an earlier cleanup threw");
    }

    /**
     * Fails its own release with something the reference count cannot explain.
     */
    private static final class CleanupFailingRequest extends DefaultFullHttpRequest {

        CleanupFailingRequest() {
            super(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        }

        @Override
        public boolean release() {
            throw new IllegalStateException("deallocator failed");
        }
    }

    /**
     * The dispatch runs off the calling thread, as the production virtual-thread executor does.
     */
    private static Thread startQuietly(Runnable task) {
        Thread worker = Thread.ofPlatform().unstarted(task);
        worker.setUncaughtExceptionHandler((_, _) -> { });
        worker.start();
        return worker;
    }

    @Test
    void shouldReleaseRequestAndPropagateWhenExecutorRejects() throws InterruptedException {
        RejectedExecutionException rejection = new RejectedExecutionException("shutting down");
        Executor rejecting = _ -> { throw rejection; };
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler((_, _, _) -> { throw new AssertionError("dispatcher must not run"); }, rejecting, connectionRegistry),
            capture);
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

        channel.writeInbound(request);

        assertEquals(0, request.refCnt(),
            "handler must release the retained request when the executor rejects the task");
        assertSame(rejection, capture.captured,
            "rejection must propagate via exceptionCaught so the pipeline can respond");
        assertNull(channel.readOutbound());
        assertTrue(connectionRegistry.awaitDispatchesFinished(0),
            "a dispatch counted before a submission that never ran must not hold the drain open");
        channel.finish();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldReportAnAbandonedDispatchItselfWhenTheEventLoopHasTerminated() throws Exception {
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        try {
            LocalChannel connection = new LocalChannel();
            group.register(connection).sync();

            CountDownLatch eventLoopTerminated = new CountDownLatch(1);
            CompletableFuture<Thread> dispatch = new CompletableFuture<>();
            connection.pipeline().addLast(new HttpRequestHandler(
                (_, _, _) -> {
                    eventLoopTerminated.await();
                    throw new IllegalStateException("The servlet context has been closed");
                },
                task -> dispatch.complete(startQuietly(task)), connectionRegistry));

            connection.pipeline().fireChannelRead(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/work"));
            Thread worker = dispatch.join();
            group.shutdownGracefully(0, 0, TimeUnit.NANOSECONDS).sync();

            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            PrintStream standardError = System.err;
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            try {
                eventLoopTerminated.countDown();
                worker.join();
            } finally {
                System.setErr(standardError);
            }
            String logged = captured.toString(StandardCharsets.UTF_8);

            assertEquals(1L, logged.lines()
                    .filter(line -> line.contains("Abandoned GET /work after the event loop terminated"))
                    .count(),
                "the handler must report the abandoned request itself, exactly once; log was: " + logged);
            assertFalse(logged.contains("Failed to submit an exceptionCaught() event."),
                "the report must not be left to Netty, which writes two stack traces for it");
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.NANOSECONDS);
        }
    }

    @Test
    void http11WithoutConnectionHeaderKeepsChannelOpen() {
        EmbeddedChannel channel = keepAliveChannel((_, _, writer) -> writer.write(emptyOkResponse()));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertFalse(out.headers().contains(HttpHeaderNames.CONNECTION),
            "HTTP/1.1 keep-alive is the default and needs no Connection header");
        assertTrue(channel.isOpen(), "HTTP/1.1 connection must be reusable after the response");
        out.release();
        channel.finish();
    }

    @Test
    void http11ConnectionCloseEchoesCloseAndClosesChannel() {
        EmbeddedChannel channel = keepAliveChannel((_, _, writer) -> writer.write(emptyOkResponse()));
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        channel.writeInbound(request);
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertEquals(HttpHeaderValues.CLOSE.toString(), out.headers().get(HttpHeaderNames.CONNECTION),
            "a requested close must be echoed on the response");
        assertFalse(channel.isOpen(), "channel must close after honouring Connection: close");
        out.release();
        channel.finish();
    }

    @Test
    void http10WithoutConnectionHeaderClosesChannel() {
        EmbeddedChannel channel = keepAliveChannel((_, _, writer) -> writer.write(emptyOkResponse()));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_0, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertEquals(HttpHeaderValues.CLOSE.toString(), out.headers().get(HttpHeaderNames.CONNECTION),
            "HTTP/1.0 defaults to close, which must be spelled out on the response");
        assertFalse(channel.isOpen(), "channel must close after an HTTP/1.0 request without keep-alive");
        out.release();
        channel.finish();
    }

    @Test
    void http10WithKeepAliveEchoesKeepAliveAndKeepsChannelOpen() {
        EmbeddedChannel channel = keepAliveChannel((_, _, writer) -> writer.write(emptyOkResponse()));
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        channel.writeInbound(request);
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertEquals(HttpHeaderValues.KEEP_ALIVE.toString(), out.headers().get(HttpHeaderNames.CONNECTION),
            "an HTTP/1.0 client only reuses the connection when keep-alive is spelled out");
        assertTrue(channel.isOpen(), "HTTP/1.0 keep-alive connection must stay open");
        out.release();
        channel.finish();
    }

    @Test
    void dispatcherConnectionCloseWinsOverKeepAliveRequest() {
        EmbeddedChannel channel = keepAliveChannel((_, _, writer) -> writer.write(closingResponse()));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertEquals(HttpHeaderValues.CLOSE.toString(), out.headers().get(HttpHeaderNames.CONNECTION),
            "a Connection: close set by the application must survive");
        assertFalse(channel.isOpen(), "channel must close when the application asks for it");
        out.release();
        channel.finish();
    }

    @Test
    void dispatcherConnectionCloseWinsOverHttp10KeepAliveRequest() {
        EmbeddedChannel channel = keepAliveChannel((_, _, writer) -> writer.write(closingResponse()));
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        channel.writeInbound(request);
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertEquals(HttpHeaderValues.CLOSE.toString(), out.headers().get(HttpHeaderNames.CONNECTION),
            "a Connection: close set by the application must not be overwritten by the HTTP/1.0 keep-alive echo");
        assertFalse(channel.isOpen(), "channel must close when the application asks for it");
        out.release();
        channel.finish();
    }

    private EmbeddedChannel keepAliveChannel(HttpRequestDispatcher dispatcher) {
        return new EmbeddedChannel(
            new HttpServerKeepAliveHandler(),
            new HttpRequestHandler(dispatcher, DIRECT, connectionRegistry));
    }

    private static FullHttpResponse emptyOkResponse() {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        return response;
    }

    private static FullHttpResponse closingResponse() {
        FullHttpResponse response = emptyOkResponse();
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        return response;
    }

    private static final class CapturingDispatcher implements HttpRequestDispatcher {

        FullHttpRequest lastRequest;
        HttpConnectionMetadata lastConnection;
        int callCount;

        @Override
        public void handle(FullHttpRequest request, HttpConnectionMetadata connection,
                           HttpResponseWriter writer) throws IOException {
            this.lastRequest = request;
            this.lastConnection = connection;
            this.callCount++;
            writer.write(emptyOkResponse());
        }
    }

    private static final class ExceptionCapturingHandler extends ChannelInboundHandlerAdapter {

        Throwable captured;

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            this.captured = cause;
        }
    }
}

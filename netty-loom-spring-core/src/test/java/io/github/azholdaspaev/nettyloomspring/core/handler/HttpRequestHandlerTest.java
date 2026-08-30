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
import io.netty.handler.codec.TooLongFrameException;
import io.github.azholdaspaev.nettyloomspring.core.support.RecordingReads;
import io.github.azholdaspaev.nettyloomspring.core.support.ReleaseFailingContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestHandlerTest {

    private static final Executor DIRECT = Runnable::run;

    /** Holds the dispatch off, so nothing drains the body while the valve is under test. */
    private static final Executor NEVER_RUN = _ -> { };

    /** Generous: it bounds a spin on another thread's progress, not the progress itself. */
    private static final Duration PARK_LIMIT = Duration.ofSeconds(5);

    private static final Duration UNREACHED_WRITE_STALL_TIMEOUT = Duration.ofSeconds(60);

    private final HttpConnectionRegistry connectionRegistry =
        new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));

    private static HttpContent bodyPart(String text) {
        return new DefaultHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
    }

    private static void receive(EmbeddedChannel channel, HttpMethod method, String uri) {
        receive(channel, new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, uri));
    }

    /**
     * Through the pipeline rather than {@code writeInbound}, which records a ClosedChannelException
     * when the response to the head has already closed the connection.
     */
    private static void receive(EmbeddedChannel channel, HttpRequest request) {
        channel.pipeline().fireChannelRead(request);
        channel.pipeline().fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
        channel.runPendingTasks();
    }

    private static void fire(Channel channel, HttpMethod method, String uri) {
        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, uri));
        channel.pipeline().fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    @Test
    void shouldWriteDispatcherResponseToChannel() {
        FullHttpResponse canned = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.EMPTY_BUFFER);
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler((_, _, _, writer) -> writer.write(canned), DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

        receive(channel, HttpMethod.GET, "/");
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertSame(canned, out, "handler must forward the dispatcher's response unchanged");
        out.release();
        channel.finish();
    }

    @Test
    void shouldWriteAStreamedResponseAsHeadChunksAndLastContent() {
        HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler((_, _, _, writer) -> {
                writer.write(head);
                writer.write(new DefaultHttpContent(Unpooled.copiedBuffer("one", StandardCharsets.UTF_8)));
                writer.write(new DefaultHttpContent(Unpooled.copiedBuffer("two", StandardCharsets.UTF_8)));
                writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
            },
            DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

        receive(channel, HttpMethod.GET, "/");
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
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler((_, _, _, writer) -> {
                writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
                writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
            },
            DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

        receive(channel, HttpMethod.GET, "/");
        channel.runPendingTasks();

        HttpResponse head = channel.readOutbound();
        assertTrue(HttpUtil.isTransferEncodingChunked(head),
            "a streaming response that declares no length must be framed as chunked");
        channel.finish();
    }

    @Test
    void shouldNotReframeAHeadThatAlreadySetContentLength() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler((_, _, _, writer) -> {
                HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                head.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 3);
                writer.write(head);
                writer.write(new DefaultHttpContent(Unpooled.copiedBuffer("abc", StandardCharsets.UTF_8)));
                writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
            },
            DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

        receive(channel, HttpMethod.GET, "/");
        channel.runPendingTasks();

        HttpResponse head = channel.readOutbound();
        assertFalse(HttpUtil.isTransferEncodingChunked(head),
            "a declared length already delimits the body; chunked on top of it would mis-frame the response");
        assertEquals(3, HttpUtil.getContentLength(head, -1L));
        readChunk(channel);
        channel.finish();
    }

    @Test
    void shouldLeaveAnHttp10StreamingHeadUnframedSoTheConnectionDelimitsIt() {
        EmbeddedChannel channel = keepAliveChannel((_, _, _, writer) -> {
            writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
            writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
        });
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        receive(channel, request);
        channel.runPendingTasks();

        HttpResponse head = channel.readOutbound();
        assertFalse(HttpUtil.isTransferEncodingChunked(head),
            "an HTTP/1.0 client cannot decode chunked framing");
        assertEquals(HttpHeaderValues.CLOSE.toString(), head.headers().get(HttpHeaderNames.CONNECTION),
            "an unframed body is delimited by the close, so the keep-alive echo must be overruled");
        assertFalse(channel.isOpen(), "the connection must close to mark the end of the body");
        channel.finish();
    }

    @Test
    void shouldReportAGoneClientAsADisconnectAndReleaseTheChunk() throws Exception {
        ByteBuf orphan = Unpooled.copiedBuffer("gone", StandardCharsets.UTF_8);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler((_, _, _, writer) -> {
                    connectionClosed.await();
                    try {
                        writer.write(new DefaultHttpContent(orphan));
                    } catch (Throwable caught) {
                        failure.set(caught);
                    }
                },
                task -> worker.set(startQuietly(task)), connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT),
            capture);

        receive(channel, HttpMethod.GET, "/");
        channel.close();
        connectionClosed.countDown();
        worker.get().join();

        assertInstanceOf(ClosedChannelException.class, failure.get(),
            "a write to a gone client must be reported as a disconnect the tail handler recognises");
        assertEquals(0, orphan.refCnt(), "the writer owns the part it could not send");
        assertNull(capture.captured,
            "a client that left is why the response stopped, not a dispatcher that broke its contract");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldParkTheDispatchThreadWhileTheConnectionIsUnwritable() throws Exception {
        CountDownLatch responseFinished = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        // On a real loop rather than an EmbeddedChannel, whose loop reports inEventLoop()
        // unconditionally true: a wait inside the writer would be refused there as a deadlock
        // instead of parking, and this test would pass against a writer that never waits.
        try (StalledConnection connection = new StalledConnection(new HttpRequestHandler((_, _, _, writer) -> {
                writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
                writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
                responseFinished.countDown();
            },
            task -> worker.set(startQuietly(task)), connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT))) {

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

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldGiveUpOnAConnectionThatStaysUnwritable() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (StalledConnection connection = new StalledConnection(new HttpRequestHandler((_, _, _, writer) -> {
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
            SpinWait.until(() -> !connection.channel.isOpen(), PARK_LIMIT,
                "the connection must be given up on, so nothing else has to reclaim it");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldWaitWithoutBoundWhenTheWriteStallTimeoutIsDisabled() throws Exception {
        CountDownLatch responseFinished = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (StalledConnection connection = new StalledConnection(new HttpRequestHandler((_, _, _, writer) -> {
                try {
                    writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
                    writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
                    responseFinished.countDown();
                } catch (Throwable caught) {
                    failure.set(caught);
                }
            },
            task -> worker.set(startQuietly(task)), connectionRegistry, Duration.ZERO))) {

            connection.dispatch();

            // WAITING rather than TIMED_WAITING is the whole of the difference: SpinWait.untilParked
            // would pass against a bound that still expires, which is what this test denies.
            SpinWait.until(() -> {
                Thread parked = worker.get();
                return parked != null && parked.getState() == Thread.State.WAITING;
            }, PARK_LIMIT, "a disabled bound must park the dispatch thread with no deadline at all");
            assertNull(failure.get(), "a disabled bound must not give up on a connection that stays unwritable");
            assertTrue(connection.channel.isOpen(), "a disabled bound must leave the connection open");

            connection.drain();
            SpinWait.until(() -> responseFinished.getCount() == 0, PARK_LIMIT,
                "the dispatch must resume once the connection has taken what it was given");
        }
    }

    @Test
    void shouldCloseInsteadOfFiringExceptionCaughtWhenTheDispatcherFailsAfterCommitting() {
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler((_, _, _, writer) -> {
                    writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
                    throw new IllegalStateException("halfway through the body");
                },
                DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT),
            capture);

        receive(channel, HttpMethod.GET, "/");
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
            new HttpRequestHandler((_, _, _, _) -> { }, DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT),
            capture);

        receive(channel, HttpMethod.GET, "/");
        channel.runPendingTasks();

        assertInstanceOf(IllegalStateException.class, capture.captured,
            "a dispatcher that writes nothing leaves the exchange hanging, so it must be reported");
        channel.finish();
    }

    @Test
    void shouldCloseAnExchangeTheDispatcherLeftHalfWritten() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler((_, _, _, writer) -> writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)),
            DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

        receive(channel, HttpMethod.GET, "/");
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
            fire(channel, HttpMethod.GET, "/");
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

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldNotRecordAClientDisconnectMidResponseAsAFailure() throws Exception {
        CountDownLatch connectionClosed = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler((_, _, _, writer) -> {
                writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
                connectionClosed.await();
                writer.write(new DefaultHttpContent(Unpooled.copiedBuffer("more", StandardCharsets.UTF_8)));
            },
            task -> worker.set(startQuietly(task)), connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

        receive(channel, HttpMethod.GET, "/download");
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

    @ParameterizedTest
    @ValueSource(ints = {100, 204, 205, 304})
    void shouldLeaveAStatusThatCanNeverCarryABodyUnframed(int status) {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler((_, _, _, writer) -> {
                writer.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status)));
                writer.write(LastHttpContent.EMPTY_LAST_CONTENT);
            },
            DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

        receive(channel, HttpMethod.GET, "/");
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
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher, DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/submit");

        receive(channel, request);
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
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher, DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

        receive(channel, HttpMethod.GET, "/");
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
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher, DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

        receive(channel, HttpMethod.GET, "/a");
        receive(channel, HttpMethod.GET, "/b");
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
            new HttpRequestHandler((_, _, _, _) -> { throw boom; }, DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT),
            capture);

        receive(channel, HttpMethod.GET, "/");
        channel.runPendingTasks();

        assertSame(boom, capture.captured,
            "exception from dispatcher must propagate via exceptionCaught");
        assertNull(channel.readOutbound(),
            "handler must not write a response when the dispatcher fails");
        channel.finish();
    }

    @Test
    void shouldHandOnTheBodyOfAnAggregatedRequestRatherThanLoseIt() {
        CompletableFuture<String> read = new CompletableFuture<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, body, _, writer) -> {
                read.complete(new String(body.readNBytes(body.available()), StandardCharsets.UTF_8));
                writer.write(emptyOkResponse());
            }, DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));
        FullHttpRequest aggregated = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/",
            Unpooled.copiedBuffer("aggregated body", StandardCharsets.UTF_8));

        channel.pipeline().fireChannelRead(aggregated);
        channel.runPendingTasks();

        assertEquals("aggregated body", read.getNow(null),
            "a pipeline that still aggregates carries the body inside the head, and a dispatcher "
                + "reading the stream would otherwise wait for content that no later message brings");
        assertEquals(0, aggregated.refCnt(), "nothing below auto-releases it, so the body owns its release");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReleaseEveryPartOfABodyNobodyRead() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher, DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));
        HttpContent unread = bodyPart("ignored");
        HttpContent terminator = new DefaultLastHttpContent();

        channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        channel.writeInbound(unread);
        channel.writeInbound(terminator);
        channel.runPendingTasks();

        assertEquals(0, unread.refCnt(), "a body the dispatcher never read is freed by the dispatch that owned it");
        assertEquals(0, terminator.refCnt());
        FullHttpResponse out = channel.readOutbound();
        assertNotNull(out);
        out.release();
        channel.finish();
    }

    @Test
    void shouldCloseRatherThanAnswerAFailureOnceTheResponseHasGoneOut() {
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        CompletableFuture<Runnable> submitted = new CompletableFuture<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(emptyOkResponse()), submitted::complete, connectionRegistry,
            UNREACHED_WRITE_STALL_TIMEOUT), capture);
        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        submitted.join().run();

        channel.pipeline().fireExceptionCaught(new TooLongFrameException("body past the limit"));

        assertNull(capture.captured,
            "a status written for the failure would encode as more of the body already sent");
        assertFalse(channel.isOpen(), "there is no way left to report it, so the connection goes");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldNotLetADispatchAnswerAnExchangeThePipelineHasAlreadyRefused() throws Exception {
        CompletableFuture<Runnable> submitted = new CompletableFuture<>();
        CompletableFuture<IOException> refusedToAnswer = new CompletableFuture<>();
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, body, _, writer) -> {
                try {
                    body.readAllBytes();
                } catch (IOException refused) {
                    try {
                        writer.write(emptyOkResponse());
                        refusedToAnswer.complete(null);
                    } catch (IOException blocked) {
                        refusedToAnswer.complete(blocked);
                    }
                }
            }, submitted::complete, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT), capture);
        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        Thread dispatch = startQuietly(submitted.join());

        channel.pipeline().fireExceptionCaught(new TooLongFrameException("body past the limit"));
        dispatch.join();
        channel.runPendingTasks();

        assertInstanceOf(ClosedChannelException.class, refusedToAnswer.join(),
            "the tail handler is answering this exchange, so a status the woken dispatch writes "
                + "would be a second response to one request");
        assertNull(channel.readOutbound(), "nothing the dispatch wrote may reach the wire");
        assertInstanceOf(TooLongFrameException.class, capture.captured,
            "and the dispatch unwinding on that refusal is its consequence, not a second failure "
                + "to map to a status");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldStillAnswerAFailureArrivingBetweenExchangesOnAReusedConnection() {
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(emptyOkResponse()), DIRECT, connectionRegistry,
            UNREACHED_WRITE_STALL_TIMEOUT), capture);
        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ok"));
        channel.pipeline().fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
        channel.runPendingTasks();

        channel.pipeline().fireExceptionCaught(new TooLongFrameException("header block past the limit"));

        assertNotNull(capture.captured,
            "the exchange before it is over, so the same failure that closes mid-response must still "
                + "reach the handler that maps it to a status");
        assertTrue(channel.isOpen(), "there is nothing on the wire a mapped status could corrupt");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldStillAnswerAFailureWhenTheDispatchOutlivesItsRequestTerminator() {
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        CompletableFuture<Runnable> submitted = new CompletableFuture<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(emptyOkResponse()), submitted::complete, connectionRegistry,
            UNREACHED_WRITE_STALL_TIMEOUT), capture);
        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ok"));
        channel.pipeline().fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
        // The terminator arrives while the response has not started, which is the order a virtual
        // thread takes and an inline executor never does: it leaves the dispatch's own cleanup as the
        // only site that can let go of the writer.
        submitted.join().run();
        channel.runPendingTasks();

        channel.pipeline().fireExceptionCaught(new TooLongFrameException("header block past the limit"));

        assertNotNull(capture.captured,
            "the exchange is over, so a failure on the connection must still reach the handler that "
                + "maps it to a status");
        assertTrue(channel.isOpen(), "there is nothing on the wire a mapped status could corrupt");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldStillAnswerAFailureWhenTheRequestTerminatorOutlivesItsDispatch() {
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        CompletableFuture<Runnable> submitted = new CompletableFuture<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(emptyOkResponse()), submitted::complete, connectionRegistry,
            UNREACHED_WRITE_STALL_TIMEOUT), capture);
        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));
        submitted.join().run();
        // The dispatch's own cleanup must run while the body is still arriving, or its writer-clearing
        // site does the work and the test passes against a version that has only that one.
        channel.runPendingTasks();

        channel.pipeline().fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
        channel.pipeline().fireExceptionCaught(new TooLongFrameException("header block past the limit"));

        assertNotNull(capture.captured,
            "the response ended before the request did, so only the terminator can settle the exchange "
                + "-- and a failure after it must still reach the handler that maps it to a status");
        assertTrue(channel.isOpen(), "there is nothing on the wire a mapped status could corrupt");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldNotAnswerAFailureThatLandsWhileTheDispatchIsWritingItsResponseHead() {
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        AtomicReference<Runnable> preemption = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(new PreemptingResponse(preemption)), DIRECT,
            connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT), capture);
        // Between write()'s guard and the wire: the response is being framed, which is where the two
        // threads interleave and where reading preempted and state separately cannot see the other.
        preemption.set(() -> channel.pipeline()
            .fireExceptionCaught(new TooLongFrameException("body past the limit")));

        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));

        assertNull(capture.captured,
            "the dispatch had already claimed the exchange, so a status mapped for the failure would "
                + "be a second response to one request");
        assertFalse(channel.isOpen(), "with no way left to report it, the connection goes instead");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldCloseOnAFailureWhileTheAlreadyAnsweredRequestIsStillArriving() {
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        CompletableFuture<Runnable> submitted = new CompletableFuture<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(emptyOkResponse()), submitted::complete, connectionRegistry,
            UNREACHED_WRITE_STALL_TIMEOUT), capture);
        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));
        submitted.join().run();
        // The dispatch's own cleanup must have run: without it the writer is still held for the
        // uninteresting reason, and the test would pass against a version that dropped it too early.
        channel.runPendingTasks();

        channel.pipeline().fireExceptionCaught(new TooLongFrameException("body past the limit"));

        assertNull(capture.captured,
            "the body is still arriving, so a status mapped for its refusal would be a second response "
                + "to one request");
        assertFalse(channel.isOpen(), "there is no way left to report it, so the connection goes");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldTakeOverEveryReadOfTheConnectionItJoins() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            new CapturingDispatcher(), NEVER_RUN, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

        assertFalse(channel.config().isAutoRead(),
            "reads must be asked for as the body is consumed, or the queue bound cannot hold");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldStopReadingWhileTheHandlerIsBehindOnTheBody() {
        RecordingReads reads = new RecordingReads();
        EmbeddedChannel channel = new EmbeddedChannel(reads, new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(emptyOkResponse()), NEVER_RUN, connectionRegistry,
            UNREACHED_WRITE_STALL_TIMEOUT));
        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        channel.pipeline().fireChannelRead(bodyPart("x".repeat(HttpRequestBodyStream.HIGH_WATERMARK_BYTES)));
        reads.count = 0;

        channel.pipeline().fireChannelReadComplete();

        assertEquals(0, reads.count,
            "reading on while nothing is draining the body would pile it up in heap, which is the "
                + "buffering this replaced");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldAskForAReadAgainOnceTheDispatchAbandonsAnUndrainedBody() {
        RecordingReads reads = new RecordingReads();
        CompletableFuture<Runnable> submitted = new CompletableFuture<>();
        EmbeddedChannel channel = new EmbeddedChannel(reads, new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(emptyOkResponse()), submitted::complete, connectionRegistry,
            UNREACHED_WRITE_STALL_TIMEOUT));
        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        channel.pipeline().fireChannelRead(bodyPart("x".repeat(HttpRequestBodyStream.HIGH_WATERMARK_BYTES)));
        channel.pipeline().fireChannelReadComplete();
        reads.count = 0;

        submitted.join().run();
        channel.runPendingTasks();

        assertEquals(1, reads.count,
            "the valve shut while the body queued and no other site reopens it, so the rest of the "
                + "upload is never drained and the connection wedges until the read timeout");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReopenTheReadValveWhenCleaningUpAnAbandonedBodyThrows() {
        RecordingReads reads = new RecordingReads();
        CompletableFuture<Runnable> submitted = new CompletableFuture<>();
        EmbeddedChannel channel = new EmbeddedChannel(reads, new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(emptyOkResponse()), submitted::complete, connectionRegistry,
            UNREACHED_WRITE_STALL_TIMEOUT));
        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        channel.pipeline().fireChannelRead(bodyPart("x".repeat(HttpRequestBodyStream.HIGH_WATERMARK_BYTES)));
        channel.pipeline().fireChannelRead(new ReleaseFailingContent());
        channel.pipeline().fireChannelReadComplete();
        reads.count = 0;

        submitted.join().run();
        channel.runPendingTasks();

        assertEquals(1, reads.count,
            "the body queue's cleanup throws by design, and letting that skip the one site that "
                + "reopens the valve wedges the connection until the read timeout");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldStillReportARejectedDispatchWhenCleaningUpItsBodyThrows() {
        RejectedExecutionException rejection = new RejectedExecutionException("shutting down");
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, _, _) -> { throw new AssertionError("dispatcher must not run"); },
            _ -> { throw rejection; }, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT),
            capture);

        channel.pipeline().fireChannelRead(new ReleaseFailingRequest());

        assertSame(rejection, capture.captured,
            "a release failure must not replace the reason the request was never dispatched");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldKeepReadingWhileTheHandlerIsKeepingUp() {
        RecordingReads reads = new RecordingReads();
        EmbeddedChannel channel = new EmbeddedChannel(reads, new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(emptyOkResponse()), NEVER_RUN, connectionRegistry,
            UNREACHED_WRITE_STALL_TIMEOUT));
        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        reads.count = 0;

        channel.pipeline().fireChannelReadComplete();

        assertEquals(1, reads.count, "a body under the bound must keep the connection reading");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReleaseAQueuedBodyWhenTheDispatcherThrows() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, _, _) -> { throw new IllegalStateException("handler failed"); },
            DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT),
            new ExceptionCapturingHandler());
        HttpContent queued = bodyPart("never read");

        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        channel.pipeline().fireChannelRead(queued);

        assertEquals(0, queued.refCnt(), "a body outliving a failed dispatch is freed by nothing else");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReleaseAQueuedBodyWhenTheConnectionDies() {
        CompletableFuture<Runnable> submitted = new CompletableFuture<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(emptyOkResponse()), submitted::complete, connectionRegistry,
            UNREACHED_WRITE_STALL_TIMEOUT));
        HttpContent queued = bodyPart("half an upload");
        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        channel.pipeline().fireChannelRead(queued);

        channel.pipeline().fireChannelInactive();

        assertEquals(0, queued.refCnt(), "a body the client abandoned must not outlive the connection");
    }

    @Test
    void shouldReleaseBodyPartsThatArriveAfterTheDispatchIsOver() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(emptyOkResponse()), DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));
        channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        channel.runPendingTasks();
        HttpContent late = bodyPart("still uploading");

        channel.writeInbound(late);

        assertEquals(0, late.refCnt(),
            "the rest of an answered request must be drained, or the connection stalls holding it");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldCountADispatchThatIsSubmittedButNotYetRunning() throws InterruptedException {
        CompletableFuture<Runnable> submitted = new CompletableFuture<>();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler((_, _, _, writer) -> writer.write(emptyOkResponse()), submitted::complete, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

        receive(channel, HttpMethod.GET, "/");

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
    void shouldCountADispatchOutExactlyOnceWhenCleaningUpItsBodyThrows() throws Exception {
        CompletableFuture<Runnable> submitted = new CompletableFuture<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(
            (_, _, _, writer) -> writer.write(emptyOkResponse()), submitted::complete, connectionRegistry,
            UNREACHED_WRITE_STALL_TIMEOUT));

        channel.pipeline().fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        channel.pipeline().fireChannelRead(new ReleaseFailingContent());
        submitted.join().run();

        assertTrue(connectionRegistry.awaitDispatchesFinished(0),
            "a dispatch whose body cleanup threw must still have been counted out");
        connectionRegistry.dispatchStarted();
        assertFalse(connectionRegistry.awaitDispatchesFinished(0),
            "and must not have been counted out a second time");
        channel.finishAndReleaseAll();
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
    void shouldReleaseTheBodyAndPropagateWhenExecutorRejects() throws InterruptedException {
        RejectedExecutionException rejection = new RejectedExecutionException("shutting down");
        Executor rejecting = _ -> { throw rejection; };
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler((_, _, _, _) -> { throw new AssertionError("dispatcher must not run"); }, rejecting, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT),
            capture);
        HttpContent orphaned = bodyPart("body of a request nobody took");

        channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
        channel.writeInbound(orphaned);

        assertEquals(0, orphaned.refCnt(),
            "a body whose dispatch was never submitted must be freed rather than queued for ever");
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
            connection.pipeline().addLast(new HttpRequestHandler((_, _, _, _) -> {
                    eventLoopTerminated.await();
                    throw new IllegalStateException("The servlet context has been closed");
                },
                task -> dispatch.complete(startQuietly(task)), connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

            fire(connection, HttpMethod.GET, "/work");
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
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldNotFailAReadWhoseAskForMoreOutlivesTheEventLoop() throws Exception {
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        try {
            LocalChannel connection = new LocalChannel();
            group.register(connection).sync();

            CountDownLatch eventLoopTerminated = new CountDownLatch(1);
            CompletableFuture<Thread> dispatch = new CompletableFuture<>();
            CompletableFuture<Throwable> read = new CompletableFuture<>();
            connection.pipeline().addLast(new HttpRequestHandler((_, body, _, _) -> {
                    eventLoopTerminated.await();
                    try {
                        body.read(new byte[HttpRequestBodyStream.LOW_WATERMARK_BYTES]);
                        read.complete(null);
                    } catch (Throwable failure) {
                        read.complete(failure);
                    }
                },
                task -> dispatch.complete(startQuietly(task)), connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));

            connection.pipeline().fireChannelRead(
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));
            // Enough that draining it crosses the low watermark, which is the only thing that asks the
            // connection for more -- and the ask is what the terminated loop rejects.
            connection.pipeline().fireChannelRead(
                bodyPart("x".repeat(HttpRequestBodyStream.LOW_WATERMARK_BYTES)));
            Thread worker = dispatch.join();
            group.shutdownGracefully(0, 0, TimeUnit.NANOSECONDS).sync();

            eventLoopTerminated.countDown();
            worker.join();

            assertNull(read.join(),
                "a read that has its bytes must not fail because the connection it would ask for more "
                    + "cannot be asked; RejectedExecutionException is unchecked, so an application's "
                    + "catch (IOException) around an upload never runs");
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.NANOSECONDS);
        }
    }

    @Test
    void http11WithoutConnectionHeaderKeepsChannelOpen() {
        EmbeddedChannel channel = keepAliveChannel((_, _, _, writer) -> writer.write(emptyOkResponse()));

        receive(channel, HttpMethod.GET, "/");
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
        EmbeddedChannel channel = keepAliveChannel((_, _, _, writer) -> writer.write(emptyOkResponse()));
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        receive(channel, request);
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
        EmbeddedChannel channel = keepAliveChannel((_, _, _, writer) -> writer.write(emptyOkResponse()));

        receive(channel, new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/"));
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
        EmbeddedChannel channel = keepAliveChannel((_, _, _, writer) -> writer.write(emptyOkResponse()));
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        receive(channel, request);
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
        EmbeddedChannel channel = keepAliveChannel((_, _, _, writer) -> writer.write(closingResponse()));

        receive(channel, HttpMethod.GET, "/");
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
        EmbeddedChannel channel = keepAliveChannel((_, _, _, writer) -> writer.write(closingResponse()));
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        receive(channel, request);
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
            new HttpRequestHandler(dispatcher, DIRECT, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT));
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

        HttpRequest lastRequest;
        HttpConnectionMetadata lastConnection;
        int callCount;

        @Override
        public void handle(HttpRequest request, InputStream body, HttpConnectionMetadata connection,
                           HttpResponseWriter writer) throws IOException {
            this.lastRequest = request;
            this.lastConnection = connection;
            this.callCount++;
            writer.write(emptyOkResponse());
        }
    }

    /**
     * An aggregated request whose body cannot be freed, which only a replacement pipeline that still
     * aggregates delivers.
     */
    private static final class ReleaseFailingRequest extends DefaultFullHttpRequest {

        ReleaseFailingRequest() {
            super(HttpVersion.HTTP_1_1, HttpMethod.POST, "/",
                Unpooled.copiedBuffer("x", StandardCharsets.UTF_8));
        }

        @Override
        public boolean release() {
            throw new IllegalStateException("deallocator failed");
        }
    }

    /**
     * Answers the exchange from the pipeline while the dispatch is inside {@code write}, which is the
     * interleaving two threads produce and a single one otherwise cannot reach.
     */
    private static final class PreemptingResponse extends DefaultHttpResponse {

        private final AtomicReference<Runnable> preemption;

        PreemptingResponse(AtomicReference<Runnable> preemption) {
            super(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            this.preemption = preemption;
        }

        @Override
        public HttpHeaders headers() {
            Runnable pending = preemption.getAndSet(null);
            if (pending != null) {
                pending.run();
            }
            return super.headers();
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

package io.github.azholdaspaev.nettyloom.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.azholdaspaev.nettyloom.core.http.DefaultNettyHttpRequest;
import io.github.azholdaspaev.nettyloom.core.http.DefaultNettyHttpResponse;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpRequest;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RequestDispatcherTest {

    @Mock
    private RequestHandler requestHandler;

    @Mock
    private ExceptionHandler exceptionHandler;

    @Mock
    private ChannelHandlerContext ctx;

    @Mock
    private ChannelFuture channelFuture;

    @Mock
    private Channel channel;

    private final ExecutorService executorService = new SynchronousExecutorService();

    private RequestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new RequestDispatcher(requestHandler, exceptionHandler, executorService, null);
    }

    @Test
    void shouldWriteResponseWhenRequestHandlerSucceeds() throws Exception {
        // Given
        givenActiveChannel();
        var request = givenRequest("/test");
        var response = givenResponse(200);
        when(requestHandler.handle(request)).thenReturn(response);

        // When
        dispatcher.channelRead(ctx, request);

        // Then
        verify(ctx).writeAndFlush(responseWithStatus(200));
    }

    @Test
    void shouldInvokeExceptionHandlerWhenRequestHandlerThrows() throws Exception {
        // Given
        givenActiveChannel();
        var request = givenRequest("/fail");
        var errorResponse = givenResponse(500);
        RuntimeException error = new RuntimeException("handler error");
        when(requestHandler.handle(request)).thenThrow(error);
        when(exceptionHandler.handle(error, request)).thenReturn(errorResponse);
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);

        // When
        dispatcher.channelRead(ctx, request);

        // Then
        verify(exceptionHandler).handle(error, request);
        verify(ctx).writeAndFlush(responseWithStatus(500));
        verify(channelFuture).addListener(ChannelFutureListener.CLOSE);
    }

    @Test
    void shouldCloseChannelWhenExceptionHandlerThrows() throws Exception {
        // Given
        var request = givenRequest("/fatal");
        RuntimeException handlerError = new RuntimeException("handler error");
        RuntimeException fallbackError = new RuntimeException("exception handler error");
        when(requestHandler.handle(request)).thenThrow(handlerError);
        when(exceptionHandler.handle(handlerError, request)).thenThrow(fallbackError);

        // When
        dispatcher.channelRead(ctx, request);

        // Then
        verify(ctx).close();
        verify(ctx, never()).writeAndFlush(any());
    }

    @Test
    void shouldForwardNonNettyHttpRequestMessages() throws Exception {
        // Given
        Object unknownMessage = "not a NettyHttpRequest";

        // When
        dispatcher.channelRead(ctx, unknownMessage);

        // Then
        verify(ctx).fireChannelRead(unknownMessage);
        verify(ctx, never()).writeAndFlush(any());
    }

    @Test
    void shouldNotWriteWhenChannelIsInactive() throws Exception {
        // Given
        givenInactiveChannel();
        var request = givenRequest("/test");
        var response = givenResponse(200);
        when(requestHandler.handle(request)).thenReturn(response);

        // When
        dispatcher.channelRead(ctx, request);

        // Then
        verify(ctx, never()).writeAndFlush(any());
    }

    @Test
    void shouldNotWriteErrorResponseWhenChannelIsInactive() throws Exception {
        // Given
        givenInactiveChannel();
        var request = givenRequest("/fail");
        RuntimeException error = new RuntimeException("handler error");
        when(requestHandler.handle(request)).thenThrow(error);
        when(exceptionHandler.handle(error, request)).thenReturn(givenResponse(500));

        // When
        dispatcher.channelRead(ctx, request);

        // Then
        verify(exceptionHandler).handle(error, request);
        verify(ctx, never()).writeAndFlush(any());
    }

    @Test
    void shouldCloseWithoutPropagatingOnPipelineException() {
        // Given
        Throwable cause = new RuntimeException("pipeline error");

        // When
        dispatcher.exceptionCaught(ctx, cause);

        // Then
        verify(ctx).close();
        verify(ctx, never()).fireExceptionCaught(any());
    }

    @Test
    void shouldNotCloseConnectionForKeepAliveRequest() throws Exception {
        // Given
        givenActiveChannel();
        var request =
                DefaultNettyHttpRequest.builder().uri("/test").keepAlive(true).build();
        when(requestHandler.handle(request)).thenReturn(givenResponse(200));
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);

        // When
        dispatcher.channelRead(ctx, request);

        // Then
        verify(ctx)
                .writeAndFlush(argThat(r -> r instanceof NettyHttpResponse resp
                        && resp.headers().get("Connection").contains("keep-alive")));
        verify(channelFuture, never()).addListener(ChannelFutureListener.CLOSE);
    }

    @Test
    void shouldCloseConnectionForNonKeepAliveRequest() throws Exception {
        // Given
        givenActiveChannel();
        var request =
                DefaultNettyHttpRequest.builder().uri("/test").keepAlive(false).build();
        when(requestHandler.handle(request)).thenReturn(givenResponse(200));
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);

        // When
        dispatcher.channelRead(ctx, request);

        // Then
        verify(ctx)
                .writeAndFlush(argThat(r -> r instanceof NettyHttpResponse resp
                        && resp.headers().get("Connection").contains("close")));
        verify(channelFuture).addListener(ChannelFutureListener.CLOSE);
    }

    @Test
    void shouldBeSharable() {
        // When / Then
        assertThat(RequestDispatcher.class).hasAnnotation(ChannelHandler.Sharable.class);
    }

    @Test
    void shouldReturn500WhenRequestTimesOut() throws Exception {
        // Given
        ExecutorService realExecutor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch blockHandler = new CountDownLatch(1);
        try {
            var timeoutDispatcher =
                    new RequestDispatcher(requestHandler, exceptionHandler, realExecutor, Duration.ofMillis(50));
            givenActiveChannel();
            var request = givenRequest("/slow");
            when(requestHandler.handle(request)).thenAnswer(invocation -> {
                blockHandler.await();
                return givenResponse(200);
            });
            when(ctx.writeAndFlush(any())).thenReturn(channelFuture);

            // When
            timeoutDispatcher.channelRead(ctx, request);

            // Then
            verify(ctx, org.mockito.Mockito.timeout(2000)).writeAndFlush(responseWithStatus(500));
            verify(channelFuture, org.mockito.Mockito.timeout(2000)).addListener(ChannelFutureListener.CLOSE);
        } finally {
            blockHandler.countDown();
            realExecutor.close();
        }
    }

    @Test
    void shouldInterruptHandlerOnTimeout() throws Exception {
        // Given
        ExecutorService realExecutor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);
        CountDownLatch handlerFinished = new CountDownLatch(1);
        try {
            var timeoutDispatcher =
                    new RequestDispatcher(requestHandler, exceptionHandler, realExecutor, Duration.ofMillis(50));
            givenActiveChannel();
            var request = givenRequest("/slow");
            when(requestHandler.handle(request)).thenAnswer(invocation -> {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    wasInterrupted.set(true);
                } finally {
                    handlerFinished.countDown();
                }
                return givenResponse(200);
            });
            when(ctx.writeAndFlush(any())).thenReturn(channelFuture);

            // When
            timeoutDispatcher.channelRead(ctx, request);

            // Then
            assertThat(handlerFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(wasInterrupted.get()).isTrue();
        } finally {
            realExecutor.close();
        }
    }

    @Test
    void shouldNotWriteTimeoutResponseWhenChannelIsInactive() throws Exception {
        // Given
        ExecutorService realExecutor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch blockHandler = new CountDownLatch(1);
        try {
            var timeoutDispatcher =
                    new RequestDispatcher(requestHandler, exceptionHandler, realExecutor, Duration.ofMillis(50));
            givenInactiveChannel();
            var request = givenRequest("/slow");
            when(requestHandler.handle(request)).thenAnswer(invocation -> {
                blockHandler.await();
                return givenResponse(200);
            });

            // When
            timeoutDispatcher.channelRead(ctx, request);

            // Then
            verify(ctx, org.mockito.Mockito.after(200).never()).writeAndFlush(any());
        } finally {
            blockHandler.countDown();
            realExecutor.close();
        }
    }

    @Test
    void shouldWriteSuccessResponseWhenHandlerCompletesBeforeTimeout() throws Exception {
        // Given
        givenActiveChannel();
        var request = givenRequest("/fast");
        when(requestHandler.handle(request)).thenReturn(givenResponse(200));
        var timeoutDispatcher =
                new RequestDispatcher(requestHandler, exceptionHandler, executorService, Duration.ofSeconds(5));

        // When
        timeoutDispatcher.channelRead(ctx, request);

        // Then
        verify(ctx).writeAndFlush(responseWithStatus(200));
    }

    @Test
    void shouldRejectNegativeRequestTimeout() {
        // When / Then
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new RequestDispatcher(requestHandler, exceptionHandler, executorService, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void givenActiveChannel() {
        when(ctx.channel()).thenReturn(channel);
        when(channel.isActive()).thenReturn(true);
    }

    private void givenInactiveChannel() {
        when(ctx.channel()).thenReturn(channel);
        when(channel.isActive()).thenReturn(false);
    }

    private static NettyHttpRequest givenRequest(String uri) {
        return DefaultNettyHttpRequest.builder().uri(uri).build();
    }

    private static NettyHttpResponse givenResponse(int statusCode) {
        return DefaultNettyHttpResponse.builder().statusCode(statusCode).build();
    }

    private static NettyHttpResponse responseWithStatus(int statusCode) {
        return argThat(r -> r instanceof NettyHttpResponse resp && resp.statusCode() == statusCode);
    }

    /**
     * An ExecutorService that runs tasks synchronously on the calling thread,
     * making virtual-thread-based code deterministic in tests.
     */
    private static final class SynchronousExecutorService extends AbstractExecutorService {

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}

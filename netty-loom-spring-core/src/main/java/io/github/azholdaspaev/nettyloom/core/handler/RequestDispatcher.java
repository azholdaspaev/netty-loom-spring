package io.github.azholdaspaev.nettyloom.core.handler;

import io.github.azholdaspaev.nettyloom.core.http.DefaultNettyHttpResponse;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpRequest;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class RequestDispatcher extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(RequestDispatcher.class);

    private final RequestHandler requestHandler;
    private final ExceptionHandler exceptionHandler;
    private final ExecutorService executorService;
    private final long requestTimeoutMillis;

    public RequestDispatcher(
            RequestHandler requestHandler,
            ExceptionHandler exceptionHandler,
            ExecutorService executorService,
            Duration requestTimeout) {
        this.requestHandler = requestHandler;
        this.exceptionHandler = exceptionHandler;
        this.executorService = executorService;
        if (requestTimeout != null && requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must not be negative");
        }
        this.requestTimeoutMillis = requestTimeout != null ? requestTimeout.toMillis() : 0;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof NettyHttpRequest request) {
            if (requestTimeoutMillis > 0) {
                dispatchWithTimeout(ctx, request);
            } else {
                dispatchWithoutTimeout(ctx, request);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private void dispatchWithoutTimeout(ChannelHandlerContext ctx, NettyHttpRequest request) {
        executorService.submit(() -> {
            try {
                NettyHttpResponse response = requestHandler.handle(request);
                if (ctx.channel().isActive()) {
                    writeResponse(ctx, response, request.keepAlive());
                }
            } catch (Exception exception) {
                handleException(ctx, request, exception);
            }
        });
    }

    private void dispatchWithTimeout(ChannelHandlerContext ctx, NettyHttpRequest request) {
        AtomicBoolean responseWritten = new AtomicBoolean(false);
        Future<?> workerFuture = executorService.submit(() -> {
            try {
                NettyHttpResponse response = requestHandler.handle(request);
                if (responseWritten.compareAndSet(false, true) && ctx.channel().isActive()) {
                    writeResponse(ctx, response, request.keepAlive());
                }
            } catch (Exception exception) {
                if (responseWritten.compareAndSet(false, true)) {
                    handleException(ctx, request, exception);
                }
            }
        });

        executorService.submit(() -> {
            try {
                workerFuture.get(requestTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                workerFuture.cancel(true);
                if (responseWritten.compareAndSet(false, true) && ctx.channel().isActive()) {
                    logger.warn("Request timed out after {}ms for {}", requestTimeoutMillis, request.uri());
                    writeErrorAndClose(
                            ctx,
                            DefaultNettyHttpResponse.builder().statusCode(500).build());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.debug("Watcher completed with exception (worker handled it)", e);
            }
        });
    }

    private void handleException(ChannelHandlerContext ctx, NettyHttpRequest request, Exception exception) {
        try {
            logger.error("Got an exception for {}", request.uri(), exception);
            NettyHttpResponse exceptionResponse = exceptionHandler.handle(exception, request);
            if (ctx.channel().isActive()) {
                writeErrorAndClose(ctx, exceptionResponse);
            }
        } catch (Exception fallback) {
            logger.error("ExceptionHandler failed for {}", request.uri(), fallback);
            ctx.close();
        }
    }

    private void writeResponse(ChannelHandlerContext ctx, NettyHttpResponse response, boolean keepAlive) {
        NettyHttpResponse finalResponse = withConnectionHeader(response, keepAlive);
        if (keepAlive) {
            ctx.writeAndFlush(finalResponse);
        } else {
            ctx.writeAndFlush(finalResponse).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void writeErrorAndClose(ChannelHandlerContext ctx, NettyHttpResponse response) {
        ctx.writeAndFlush(withConnectionHeader(response, false)).addListener(ChannelFutureListener.CLOSE);
    }

    private NettyHttpResponse withConnectionHeader(NettyHttpResponse response, boolean keepAlive) {
        Map<String, List<String>> headers =
                new LinkedHashMap<>(response.headers() != null ? response.headers() : Map.of());
        headers.put("Connection", List.of(keepAlive ? "keep-alive" : "close"));
        return DefaultNettyHttpResponse.builder()
                .statusCode(response.statusCode())
                .headers(headers)
                .body(response.body())
                .build();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Unhandled pipeline exception, closing channel {}", ctx.channel(), cause);
        ctx.close();
    }
}

package io.github.azholdaspaev.nettyloom.core.handler;

import io.github.azholdaspaev.nettyloom.core.http.DefaultNettyHttpResponse;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpRequest;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class RequestDispatcher extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(RequestDispatcher.class);

    private final RequestHandler requestHandler;
    private final ExceptionHandler exceptionHandler;
    private final ExecutorService executorService;

    public RequestDispatcher(
            RequestHandler requestHandler, ExceptionHandler exceptionHandler, ExecutorService executorService) {
        this.requestHandler = requestHandler;
        this.exceptionHandler = exceptionHandler;
        this.executorService = executorService;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof NettyHttpRequest request) {
            executorService.submit(() -> {
                try {
                    NettyHttpResponse response = requestHandler.handle(request);
                    if (ctx.channel().isActive()) {
                        writeResponse(ctx, response, request.keepAlive());
                    }
                } catch (Exception exception) {
                    try {
                        logger.error("Got an exception for {}", request.uri(), exception);
                        NettyHttpResponse exceptionResponse = exceptionHandler.handle(exception, request);
                        if (ctx.channel().isActive()) {
                            ctx.writeAndFlush(withConnectionHeader(exceptionResponse, false))
                                    .addListener(ChannelFutureListener.CLOSE);
                        }
                    } catch (Exception fallback) {
                        logger.error("ExceptionHandler failed for {}", request.uri(), fallback);
                        ctx.close();
                    }
                }
            });
        } else {
            super.channelRead(ctx, msg);
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

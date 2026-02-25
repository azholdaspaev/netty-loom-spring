package io.github.azholdaspaev.nettyloom.core.handler;

import io.github.azholdaspaev.nettyloom.core.http.NettyHttpRequest;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
                        ctx.writeAndFlush(response);
                    }
                } catch (Exception exception) {
                    try {
                        NettyHttpResponse exceptionResponse = exceptionHandler.handle(exception, request);
                        if (ctx.channel().isActive()) {
                            ctx.writeAndFlush(exceptionResponse);
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

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Unhandled pipeline exception, closing channel {}", ctx.channel(), cause);
        ctx.close();
    }
}

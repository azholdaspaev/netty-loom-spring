package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;

@Sharable
public class HttpExceptionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpExceptionHandler.class);

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (isClientDisconnect(cause)) {
            log.debug("Client disconnected before response", cause);
            if (ctx.channel().isActive()) {
                ctx.close();
            }
            return;
        }

        HttpResponseStatus status = statusFor(cause);
        logException(status, cause);

        if (!ctx.channel().isActive()) {
            return;
        }

        ctx.writeAndFlush(buildResponse(status))
            .addListener(ChannelFutureListener.CLOSE);
    }

    private static HttpResponseStatus statusFor(Throwable cause) {
        Throwable unwrapped = cause instanceof DecoderException && cause.getCause() != null
            ? cause.getCause()
            : cause;

        if (unwrapped instanceof TooLongFrameException) {
            return HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE;
        }
        if (unwrapped instanceof DecoderException || unwrapped instanceof IllegalArgumentException) {
            return HttpResponseStatus.BAD_REQUEST;
        }
        if (unwrapped instanceof UnsupportedOperationException) {
            return HttpResponseStatus.NOT_IMPLEMENTED;
        }
        return HttpResponseStatus.INTERNAL_SERVER_ERROR;
    }

    private static FullHttpResponse buildResponse(HttpResponseStatus status) {
        ByteBuf body = Unpooled.copiedBuffer(status.reasonPhrase(), StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, body);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        return response;
    }

    private static boolean isClientDisconnect(Throwable cause) {
        if (cause instanceof ClosedChannelException) {
            return true;
        }
        if (!(cause instanceof IOException)) {
            return false;
        }
        String message = cause.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("Connection reset")
            || message.contains("Broken pipe")
            || message.contains("forcibly closed");
    }

    private static void logException(HttpResponseStatus status, Throwable cause) {
        if (status.code() >= 500) {
            log.error("Pipeline exception → {}", status, cause);
        } else {
            log.warn("Pipeline exception → {}", status, cause);
        }
    }
}

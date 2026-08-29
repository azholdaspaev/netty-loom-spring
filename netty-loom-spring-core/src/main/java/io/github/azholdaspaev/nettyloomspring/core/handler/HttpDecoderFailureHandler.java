package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpObject;
import io.netty.util.ReferenceCountUtil;

/**
 * Rejects a message the codec could not parse (issue #138). {@code HttpObjectDecoder.invalidMessage}
 * does not throw on an over-limit initial line or header block: it marks the message with a failed
 * {@code DecoderResult} and forwards it. Without this the request reaches the application with the
 * oversized header simply missing.
 */
@Sharable
public class HttpDecoderFailureHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpObject part && !part.decoderResult().isSuccess()) {
            Throwable cause = part.decoderResult().cause();
            ReferenceCountUtil.release(msg);
            ctx.fireExceptionCaught(cause);
            return;
        }
        ctx.fireChannelRead(msg);
    }
}

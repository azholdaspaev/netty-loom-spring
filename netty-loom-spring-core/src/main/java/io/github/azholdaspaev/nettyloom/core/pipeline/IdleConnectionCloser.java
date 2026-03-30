package io.github.azholdaspaev.nettyloom.core.pipeline;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
class IdleConnectionCloser extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(IdleConnectionCloser.class);

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent idleEvent) {
            logger.debug("Closing idle connection {} (state={})", ctx.channel(), idleEvent.state());
            ctx.close();
        } else {
            super.userEventTriggered(ctx, event);
        }
    }
}

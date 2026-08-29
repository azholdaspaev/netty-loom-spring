package io.github.azholdaspaev.nettyloomspring.core.support;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;

/**
 * Counts the reads that escape towards the head, which is the only place a withheld one shows. Put
 * first in the pipeline, so it sees what actually reached the connection.
 */
public final class RecordingReads extends ChannelOutboundHandlerAdapter {

    public int count;

    @Override
    public void read(ChannelHandlerContext ctx) {
        count++;
        ctx.read();
    }
}

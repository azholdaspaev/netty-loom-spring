package io.azholdaspaev.nettyloom.core.pipeline;

import io.netty.channel.ChannelHandler;

public record NamedChannelHandler(
    String name,
    ChannelHandler channelHandler
) {
}

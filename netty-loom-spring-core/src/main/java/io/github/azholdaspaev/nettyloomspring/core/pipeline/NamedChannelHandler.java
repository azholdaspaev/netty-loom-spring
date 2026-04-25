package io.github.azholdaspaev.nettyloomspring.core.pipeline;

import io.netty.channel.ChannelHandler;

public record NamedChannelHandler(
    String name,
    ChannelHandler channelHandler
) {
}

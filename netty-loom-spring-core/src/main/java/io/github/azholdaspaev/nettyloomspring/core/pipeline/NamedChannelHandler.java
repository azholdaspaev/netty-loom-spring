package io.github.azholdaspaev.nettyloomspring.core.pipeline;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;

import java.util.function.Supplier;

public record NamedChannelHandler(
    String name,
    Supplier<? extends ChannelHandler> factory
) {

    public static NamedChannelHandler shared(String name, ChannelHandler handler) {
        if (!handler.getClass().isAnnotationPresent(Sharable.class)) {
            throw new IllegalArgumentException(
                "Handler " + handler.getClass().getName() + " is not @Sharable; cannot be reused across channels"
            );
        }
        return new NamedChannelHandler(name, () -> handler);
    }
}

package io.github.azholdaspaev.nettyloomspring.core.pipeline;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NamedChannelHandlerTest {

    @Test
    void sharedShouldReturnSameInstanceOnEveryFactoryCall() {
        ChannelHandler handler = new SharableHandler();

        NamedChannelHandler step = NamedChannelHandler.shared("name", handler);

        assertSame(handler, step.factory().get());
        assertSame(handler, step.factory().get());
    }

    @Test
    void sharedShouldRejectNonSharableHandler() {
        ChannelHandler nonSharable = new ChannelInboundHandlerAdapter();

        assertThrows(IllegalArgumentException.class,
            () -> NamedChannelHandler.shared("name", nonSharable));
    }

    @Sharable
    private static final class SharableHandler extends ChannelInboundHandlerAdapter {
    }
}

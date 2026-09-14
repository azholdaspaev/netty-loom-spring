package io.github.azholdaspaev.nettyloomspring.core.pipeline;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NettyPipelineStepTest {

    @Test
    void shouldReturnSameInstanceOnEveryFactoryCall() {
        ChannelHandler handler = new SharableHandler();

        NettyPipelineStep step = NettyPipelineStep.shared("name", handler);

        assertSame(handler, step.factory().get());
        assertSame(handler, step.factory().get());
    }

    @Test
    void shouldRejectNonSharableHandler() {
        ChannelHandler nonSharable = new ChannelInboundHandlerAdapter();

        assertThrows(IllegalArgumentException.class,
            () -> NettyPipelineStep.shared("name", nonSharable));
    }

    @Sharable
    private static final class SharableHandler extends ChannelInboundHandlerAdapter {
    }
}

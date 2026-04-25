package io.github.azholdaspaev.nettyloomspring.core.pipeline;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DefaultNettyPipelineConfigurerTest {

    @Test
    void shouldConfigureEmptyPipeline() {
        var configurer = new DefaultNettyPipelineConfigurer(List.of());
        var channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();

        configurer.configure(pipeline);

        assertNull(pipeline.get("nonexistent"));
    }

    @Test
    void shouldAddSingleHandlerToPipeline() {
        var handler = new ChannelInboundHandlerAdapter();
        var configurer = new DefaultNettyPipelineConfigurer(List.of(
                new NamedChannelHandler("myHandler", handler)
        ));
        var channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();

        configurer.configure(pipeline);

        assertSame(handler, pipeline.get("myHandler"));
    }

    @Test
    void shouldAddMultipleHandlersInOrder() {
        var first = new ChannelInboundHandlerAdapter();
        var second = new ChannelInboundHandlerAdapter();
        var third = new ChannelInboundHandlerAdapter();
        var configurer = new DefaultNettyPipelineConfigurer(List.of(
                new NamedChannelHandler("first", first),
                new NamedChannelHandler("second", second),
                new NamedChannelHandler("third", third)
        ));
        var channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();

        configurer.configure(pipeline);

        assertSame(first, pipeline.get("first"));
        assertSame(second, pipeline.get("second"));
        assertSame(third, pipeline.get("third"));

        List<String> names = pipeline.names();
        int firstIdx = names.indexOf("first");
        int secondIdx = names.indexOf("second");
        int thirdIdx = names.indexOf("third");
        assertEquals(firstIdx + 1, secondIdx);
        assertEquals(secondIdx + 1, thirdIdx);
    }

    @Test
    void shouldDefensivelyCopyHandlerList() {
        var handler = new ChannelInboundHandlerAdapter();
        var mutableList = new ArrayList<>(List.of(
                new NamedChannelHandler("original", handler)
        ));
        var configurer = new DefaultNettyPipelineConfigurer(mutableList);

        mutableList.clear();

        var channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();
        configurer.configure(pipeline);

        assertSame(handler, pipeline.get("original"));
    }

}

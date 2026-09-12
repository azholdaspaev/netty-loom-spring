package io.github.azholdaspaev.nettyloomspring.core.pipeline;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NettyPipelineDefinitionTest {

    @Test
    void shouldApplyEmptyDefinitionToPipeline() {
        var definition = new NettyPipelineDefinition(List.of());
        var channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();

        definition.applyTo(pipeline);

        assertNull(pipeline.get("nonexistent"));
    }

    @Test
    void shouldAddSingleHandlerToPipeline() {
        var handler = new ChannelInboundHandlerAdapter();
        var definition = new NettyPipelineDefinition(List.of(
                new NettyPipelineStep("myHandler", () -> handler)
        ));
        var channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();

        definition.applyTo(pipeline);

        assertSame(handler, pipeline.get("myHandler"));
    }

    @Test
    void shouldAddMultipleHandlersInOrder() {
        var first = new ChannelInboundHandlerAdapter();
        var second = new ChannelInboundHandlerAdapter();
        var third = new ChannelInboundHandlerAdapter();
        var definition = new NettyPipelineDefinition(List.of(
                new NettyPipelineStep("first", () -> first),
                new NettyPipelineStep("second", () -> second),
                new NettyPipelineStep("third", () -> third)
        ));
        var channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();

        definition.applyTo(pipeline);

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
                new NettyPipelineStep("original", () -> handler)
        ));
        var definition = new NettyPipelineDefinition(mutableList);

        mutableList.clear();

        var channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();
        definition.applyTo(pipeline);

        assertSame(handler, pipeline.get("original"));
    }

    @Test
    void shouldCreateFreshHandlerInstancePerApplyToCall() {
        var definition = new NettyPipelineDefinition(List.of(
                new NettyPipelineStep("perChannel", ChannelInboundHandlerAdapter::new)
        ));

        ChannelPipeline firstPipeline = new EmbeddedChannel().pipeline();
        ChannelPipeline secondPipeline = new EmbeddedChannel().pipeline();
        definition.applyTo(firstPipeline);
        definition.applyTo(secondPipeline);

        var firstHandler = firstPipeline.get("perChannel");
        var secondHandler = secondPipeline.get("perChannel");
        assertNotNull(firstHandler);
        assertNotNull(secondHandler);
        assertNotSame(firstHandler, secondHandler);
    }

    @Test
    void shouldInsertAStepDirectlyAfterTheNamedOneWithoutChangingTheOriginal() {
        var original = new NettyPipelineDefinition(List.of(
                new NettyPipelineStep("first", ChannelInboundHandlerAdapter::new),
                new NettyPipelineStep("second", ChannelInboundHandlerAdapter::new)
        ));

        var extended = original.withStepAfter("first", new NettyPipelineStep("inserted", ChannelInboundHandlerAdapter::new));

        ChannelPipeline extendedPipeline = new EmbeddedChannel().pipeline();
        extended.applyTo(extendedPipeline);
        assertEquals(List.of("first", "inserted", "second"), extendedPipeline.names().subList(0, 3),
                "the new step must sit directly after the named one");

        ChannelPipeline originalPipeline = new EmbeddedChannel().pipeline();
        original.applyTo(originalPipeline);
        assertNull(originalPipeline.get("inserted"), "the original definition must not gain the step");
    }

    @Test
    void shouldRejectInsertingAfterAStepThatDoesNotExist() {
        var definition = new NettyPipelineDefinition(List.of(
                new NettyPipelineStep("first", ChannelInboundHandlerAdapter::new)
        ));
        var step = new NettyPipelineStep("inserted", ChannelInboundHandlerAdapter::new);

        assertThrows(IllegalArgumentException.class, () -> definition.withStepAfter("missing", step),
                "a misspelled anchor must fail when the definition is built, not once per channel");
    }

}

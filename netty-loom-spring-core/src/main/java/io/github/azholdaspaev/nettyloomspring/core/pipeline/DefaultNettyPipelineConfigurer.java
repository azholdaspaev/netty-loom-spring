package io.github.azholdaspaev.nettyloomspring.core.pipeline;

import io.netty.channel.ChannelPipeline;

import java.util.List;
import java.util.Objects;

public class DefaultNettyPipelineConfigurer implements NettyPipelineConfigurer {

    private final List<NamedChannelHandler> pipelineSteps;

    public DefaultNettyPipelineConfigurer(List<NamedChannelHandler> pipelineSteps) {
        this.pipelineSteps = List.copyOf(pipelineSteps);
    }

    @Override
    public void configure(ChannelPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline must not be null");

        pipelineSteps.forEach(step -> pipeline.addLast(step.name(), step.channelHandler()));
    }
}

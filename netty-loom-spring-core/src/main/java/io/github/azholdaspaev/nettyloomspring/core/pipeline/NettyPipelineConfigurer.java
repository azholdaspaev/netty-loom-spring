package io.github.azholdaspaev.nettyloomspring.core.pipeline;

import io.netty.channel.ChannelPipeline;

import java.util.List;
import java.util.Objects;

public class NettyPipelineConfigurer {

    private final List<NamedChannelHandler> pipelineSteps;

    public NettyPipelineConfigurer(List<NamedChannelHandler> pipelineSteps) {
        this.pipelineSteps = List.copyOf(pipelineSteps);
    }

    public void configure(ChannelPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline must not be null");

        pipelineSteps.forEach(step -> pipeline.addLast(step.name(), step.factory().get()));
    }
}

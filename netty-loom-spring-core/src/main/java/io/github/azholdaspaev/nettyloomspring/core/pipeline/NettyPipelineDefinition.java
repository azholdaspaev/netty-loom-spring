package io.github.azholdaspaev.nettyloomspring.core.pipeline;

import io.netty.channel.ChannelPipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NettyPipelineDefinition {

    private final List<NettyPipelineStep> pipelineSteps;

    public NettyPipelineDefinition(List<NettyPipelineStep> pipelineSteps) {
        this.pipelineSteps = List.copyOf(pipelineSteps);
    }

    public NettyPipelineDefinition withStepAfter(String name, NettyPipelineStep step) {
        int anchor = -1;
        for (int i = 0; i < pipelineSteps.size(); i++) {
            if (pipelineSteps.get(i).name().equals(name)) {
                anchor = i;
            }
        }
        if (anchor < 0) {
            throw new IllegalArgumentException("No pipeline step named '" + name + "'");
        }
        List<NettyPipelineStep> extended = new ArrayList<>(pipelineSteps);
        extended.add(anchor + 1, step);
        return new NettyPipelineDefinition(extended);
    }

    public void applyTo(ChannelPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline must not be null");

        pipelineSteps.forEach(step -> pipeline.addLast(step.name(), step.factory().get()));
    }
}

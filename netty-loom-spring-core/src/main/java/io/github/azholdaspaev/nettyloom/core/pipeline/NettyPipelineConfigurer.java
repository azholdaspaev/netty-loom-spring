package io.github.azholdaspaev.nettyloom.core.pipeline;

import io.netty.channel.ChannelPipeline;

public interface NettyPipelineConfigurer {

    void configure(ChannelPipeline pipeline);
}

package io.github.azholdaspaev.nettyloomspring.core.pipeline;

import io.netty.channel.ChannelPipeline;

public interface NettyPipelineConfigurer {

    void configure(ChannelPipeline pipeline);
}

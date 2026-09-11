package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineDefinition;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public class NettyServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final NettyPipelineDefinition nettyPipelineDefinition;
    private final HttpConnectionRegistry connectionRegistry;

    public NettyServerChannelInitializer(NettyPipelineDefinition nettyPipelineDefinition,
                                         HttpConnectionRegistry connectionRegistry) {
        this.nettyPipelineDefinition = nettyPipelineDefinition;
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        connectionRegistry.register(ch);
        nettyPipelineDefinition.applyTo(ch.pipeline());
    }
}

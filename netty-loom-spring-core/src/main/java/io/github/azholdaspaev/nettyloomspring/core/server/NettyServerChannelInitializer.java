package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineConfigurer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public class NettyServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final NettyPipelineConfigurer nettyPipelineConfigurer;
    private final HttpConnectionRegistry connectionRegistry;

    public NettyServerChannelInitializer(NettyPipelineConfigurer nettyPipelineConfigurer,
                                         HttpConnectionRegistry connectionRegistry) {
        this.nettyPipelineConfigurer = nettyPipelineConfigurer;
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        connectionRegistry.register(ch);
        nettyPipelineConfigurer.configure(ch.pipeline());
    }
}

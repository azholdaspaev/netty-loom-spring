package io.github.azholdaspaev.nettyloom.core.server;

import io.github.azholdaspaev.nettyloom.core.pipeline.NettyPipelineConfigurer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public class NettyServerInitializer extends ChannelInitializer<SocketChannel> {

    private final NettyPipelineConfigurer nettyPipelineConfigurer;

    public NettyServerInitializer(NettyPipelineConfigurer nettyPipelineConfigurer) {
        this.nettyPipelineConfigurer = nettyPipelineConfigurer;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        nettyPipelineConfigurer.configure(ch.pipeline());
    }
}

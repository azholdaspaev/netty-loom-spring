package io.azholdaspaev.nettyloom.core.server;

import io.azholdaspaev.nettyloom.core.pipeline.NettyPipelineConfigurer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public class NettyServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final NettyPipelineConfigurer nettyPipelineConfigurer;

    public NettyServerChannelInitializer(NettyPipelineConfigurer nettyPipelineConfigurer) {
        this.nettyPipelineConfigurer = nettyPipelineConfigurer;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        nettyPipelineConfigurer.configure(ch.pipeline());
    }
}

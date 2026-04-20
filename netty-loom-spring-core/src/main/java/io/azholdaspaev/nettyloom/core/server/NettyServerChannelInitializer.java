package io.azholdaspaev.nettyloom.core.server;

import io.azholdaspaev.nettyloom.core.pipeline.NettyPipelineConfigurer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.SocketChannel;

public class NettyServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final NettyPipelineConfigurer nettyPipelineConfigurer;
    private final ChannelGroup channelGroup;

    public NettyServerChannelInitializer(NettyPipelineConfigurer nettyPipelineConfigurer, ChannelGroup channelGroup) {
        this.nettyPipelineConfigurer = nettyPipelineConfigurer;
        this.channelGroup = channelGroup;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        channelGroup.add(ch);
        nettyPipelineConfigurer.configure(ch.pipeline());
    }
}

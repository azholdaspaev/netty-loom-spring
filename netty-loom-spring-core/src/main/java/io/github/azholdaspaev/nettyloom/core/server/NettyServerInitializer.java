package io.github.azholdaspaev.nettyloom.core.server;

import io.github.azholdaspaev.nettyloom.core.handler.ExceptionHandler;
import io.github.azholdaspaev.nettyloom.core.handler.RequestDispatcher;
import io.github.azholdaspaev.nettyloom.core.handler.RequestHandler;
import io.github.azholdaspaev.nettyloom.core.pipeline.NettyPipelineConfigurer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import java.util.concurrent.Executors;

public class NettyServerInitializer extends ChannelInitializer<SocketChannel> {

    private final RequestHandler requestHandler;
    private final ExceptionHandler exceptionHandler;
    private final NettyPipelineConfigurer nettyPipelineConfigurer;

    public NettyServerInitializer(
            RequestHandler requestHandler,
            ExceptionHandler exceptionHandler,
            NettyPipelineConfigurer nettyPipelineConfigurer) {
        this.requestHandler = requestHandler;
        this.exceptionHandler = exceptionHandler;
        this.nettyPipelineConfigurer = nettyPipelineConfigurer;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        nettyPipelineConfigurer.configure(ch.pipeline());

        ch.pipeline()
                .addLast(
                        "dispatcher",
                        new RequestDispatcher(
                                requestHandler, exceptionHandler, Executors.newVirtualThreadPerTaskExecutor()));
    }
}

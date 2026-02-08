package io.github.azholdaspaev.nettyloom.core.server;

import io.github.azholdaspaev.nettyloom.core.handler.ExceptionHandler;
import io.github.azholdaspaev.nettyloom.core.handler.RequestDispatcher;
import io.github.azholdaspaev.nettyloom.core.handler.RequestHandler;
import io.github.azholdaspaev.nettyloom.core.pipeline.HttpServerPipelineConfigurer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.util.concurrent.atomic.AtomicReference;

public class NettyServer {

    private final NettyServerConfig config;
    private final RequestHandler requestHandler;
    private final ExceptionHandler exceptionHandler;
    private final AtomicReference<NettyServerState> state;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyServer(NettyServerConfig config, RequestHandler requestHandler, ExceptionHandler exceptionHandler) {
        this.config = config;
        this.requestHandler = requestHandler;
        this.exceptionHandler = exceptionHandler;
        this.state = new AtomicReference<>(NettyServerState.CREATED);
    }

    public void start() throws InterruptedException {
        if (!state.compareAndSet(NettyServerState.CREATED, NettyServerState.STARTING)) {
            return;
        }

        try {
            int workers = config.workerThreads() == 0
                    ? Runtime.getRuntime().availableProcessors() * 2
                    : config.workerThreads();

            bossGroup = new NioEventLoopGroup(config.bossThreads());
            workerGroup = new NioEventLoopGroup(workers);

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            HttpServerPipelineConfigurer pipelineConfigurer = new HttpServerPipelineConfigurer(config);
                            pipelineConfigurer.configure(ch.pipeline());

                            ch.pipeline()
                                    .addLast("dispatcher", new RequestDispatcher(requestHandler, exceptionHandler));
                        }
                    });

            ChannelFuture bindFuture = bootstrap.bind(8080).sync();
            serverChannel = bindFuture.channel();
        } catch (Exception e) {
            state.set(NettyServerState.STOPPED);
            shutdown(bossGroup, workerGroup);
            throw new IllegalStateException(e);
        }
    }

    public void stop() {
        if (!state.compareAndSet(NettyServerState.RUNNING, NettyServerState.STOPPING)) {
            return;
        }

        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }

            shutdown(bossGroup, workerGroup);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            state.set(NettyServerState.STOPPED);
        }
    }

    private void shutdown(EventLoopGroup... groups) {
        for (EventLoopGroup group : groups) {
            if (group != null) {
                group.shutdownGracefully();
            }
        }
    }
}

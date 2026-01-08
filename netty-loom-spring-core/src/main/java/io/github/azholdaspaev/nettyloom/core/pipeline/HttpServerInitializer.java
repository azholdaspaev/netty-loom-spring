package io.github.azholdaspaev.nettyloom.core.pipeline;

import io.github.azholdaspaev.nettyloom.core.handler.HttpRequestHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.util.concurrent.ExecutorService;

/**
 * Channel initializer that sets up the HTTP pipeline.
 */
public class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    private final int maxContentLength;
    private final ExecutorService executor;

    public HttpServerInitializer(int maxContentLength, ExecutorService executor) {
        this.maxContentLength = maxContentLength;
        this.executor = executor;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // HTTP codec: encodes/decodes HTTP messages
        pipeline.addLast("httpCodec", new HttpServerCodec());

        // Aggregates HTTP message parts into FullHttpRequest
        pipeline.addLast("httpAggregator", new HttpObjectAggregator(maxContentLength));

        // Our request handler that dispatches to virtual threads
        pipeline.addLast("httpHandler", new HttpRequestHandler(executor));
    }
}

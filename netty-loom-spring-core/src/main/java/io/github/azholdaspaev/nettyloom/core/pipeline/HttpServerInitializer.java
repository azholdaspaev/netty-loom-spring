package io.github.azholdaspaev.nettyloom.core.pipeline;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

/**
 * Channel initializer that sets up the HTTP pipeline.
 *
 * <p>The pipeline consists of:
 * <ul>
 *   <li>HttpServerCodec - encodes/decodes HTTP messages</li>
 *   <li>HttpObjectAggregator - combines HTTP chunks into FullHttpRequest</li>
 * </ul>
 */
public class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    private final int maxContentLength;
    private final ChannelHandler requestHandler;

    /**
     * Creates initializer with a custom request handler.
     * Use this constructor for Spring Boot integration with SpringMvcBridgeHandler.
     *
     * @param maxContentLength maximum content length for HTTP aggregation
     * @param requestHandler the handler to process HTTP requests
     */
    public HttpServerInitializer(int maxContentLength, ChannelHandler requestHandler) {
        this.maxContentLength = maxContentLength;
        this.requestHandler = requestHandler;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // HTTP codec: encodes/decodes HTTP messages
        pipeline.addLast("httpCodec", new HttpServerCodec());

        // Aggregates HTTP message parts into FullHttpRequest
        pipeline.addLast("httpAggregator", new HttpObjectAggregator(maxContentLength));

        // Request handler
        pipeline.addLast("httpHandler", requestHandler);
    }
}

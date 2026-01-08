package io.github.azholdaspaev.nettyloom.core.pipeline;

import io.github.azholdaspaev.nettyloom.core.handler.HttpRequestHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.util.concurrent.ExecutorService;

/**
 * Channel initializer that sets up the HTTP pipeline.
 *
 * <p>The pipeline consists of:
 * <ul>
 *   <li>HttpServerCodec - encodes/decodes HTTP messages</li>
 *   <li>HttpObjectAggregator - combines HTTP chunks into FullHttpRequest</li>
 *   <li>Request handler - either custom injected or default HttpRequestHandler</li>
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

    /**
     * Creates initializer with the default HttpRequestHandler.
     * This constructor maintains backwards compatibility for standalone server usage.
     *
     * @param maxContentLength maximum content length for HTTP aggregation
     * @param executor the executor service for virtual thread dispatch
     */
    public HttpServerInitializer(int maxContentLength, ExecutorService executor) {
        this(maxContentLength, new HttpRequestHandler(executor));
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // HTTP codec: encodes/decodes HTTP messages
        pipeline.addLast("httpCodec", new HttpServerCodec());

        // Aggregates HTTP message parts into FullHttpRequest
        pipeline.addLast("httpAggregator", new HttpObjectAggregator(maxContentLength));

        // Request handler (custom or default)
        pipeline.addLast("httpHandler", requestHandler);
    }
}

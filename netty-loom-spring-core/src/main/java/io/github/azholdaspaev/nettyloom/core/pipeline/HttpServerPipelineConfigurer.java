package io.github.azholdaspaev.nettyloom.core.pipeline;

import io.github.azholdaspaev.nettyloom.core.server.NettyServerConfig;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.concurrent.TimeUnit;

public class HttpServerPipelineConfigurer {

    private final NettyServerConfig config;

    public HttpServerPipelineConfigurer(NettyServerConfig config) {
        this.config = config;
    }

    public void configure(ChannelPipeline pipeline) {
        pipeline.addLast(
                "httpCodec",
                new HttpServerCodec(config.maxInitialLineLength(), config.maxHeaderSize(), config.maxChunkSize())
        );

        pipeline.addLast("aggregator", new HttpObjectAggregator(config.maxInitialLineLength()));

        pipeline.addLast("idleState", new IdleStateHandler(config.idleTimeout().toSeconds(), 0, 0, TimeUnit.SECONDS));

        pipeline.addLast("requestDecoder", new HttpRequestDecoder());

        pipeline.addLast("responseEncoder", new HttpResponseEncoder());
    }
}

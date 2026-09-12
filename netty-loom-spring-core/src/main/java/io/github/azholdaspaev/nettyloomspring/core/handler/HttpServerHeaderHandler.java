package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Stamps the configured {@code Server} header on every response head (issue #167). Sits directly
 * above the codec so the rejections other handlers write past the dispatcher carry it too.
 */
@Sharable
public class HttpServerHeaderHandler extends ChannelOutboundHandlerAdapter {

    private final String serverHeader;

    public HttpServerHeaderHandler(String serverHeader) {
        this.serverHeader = serverHeader;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof HttpResponse response) {
            // set, not setIfAbsent: Tomcat's configured value overrides anything the application set
            // (Http11Processor.prepareResponse, "server always overrides anything the app might set").
            response.headers().set(HttpHeaderNames.SERVER, serverHeader);
        }
        ctx.write(msg, promise);
    }
}

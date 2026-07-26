package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Marks a connection busy while it is serving an HTTP exchange, so graceful shutdown can tell a
 * connection that owes a response from one merely being held open (issue #67).
 *
 * <p>Belongs <em>above</em> the aggregator. A connection is busy from the head of a request, not
 * from the moment the aggregator has assembled it — a body arriving in a later TCP segment (any
 * sizeable upload, a slow client, a 100-continue flow) would otherwise leave the connection looking
 * idle, and a drain starting in that window would reset the request it is supposed to protect.
 *
 * <p>While draining, the response carries {@code Connection: close} so a well-behaved client stops
 * pooling the connection and {@code HttpServerKeepAliveHandler} closes it once the response is
 * written. Only the <em>last</em> response owed: that handler closes on the first non-keep-alive
 * response it sees, which on a pipelined connection would strand every response still queued behind
 * it.
 */
public class HttpDrainHandler extends ChannelDuplexHandler {

    private final HttpConnectionRegistry connectionRegistry;

    public HttpDrainHandler(HttpConnectionRegistry connectionRegistry) {
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            connectionRegistry.exchangeStarted(ctx.channel());
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof HttpResponse response && isLastResponseOwedWhileDraining(ctx)) {
            HttpUtil.setKeepAlive(response.headers(), response.protocolVersion(), false);
        }
        // The exchange ends when the response is actually on the wire, not when it is queued.
        if (msg instanceof LastHttpContent) {
            promise.addListener(_ -> connectionRegistry.exchangeFinished(ctx.channel()));
        }
        ctx.write(msg, promise);
    }

    private boolean isLastResponseOwedWhileDraining(ChannelHandlerContext ctx) {
        return connectionRegistry.isDraining() && connectionRegistry.inFlight(ctx.channel()) <= 1;
    }
}

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
 * connection that owes a response from one merely being held open (issue #67). Sits above the
 * codec, so a connection counts as busy from the head of a request: a body arriving in a later TCP
 * segment would otherwise leave it looking idle, and a drain starting in that window would reset the
 * request it is meant to protect. While draining, only the last response owed carries
 * {@code Connection: close} — {@code HttpServerKeepAliveHandler} closes on the first non-keep-alive
 * response it sees, which on a pipelined connection would strand everything queued behind it. One
 * whose head already left carries none; the close at zero in-flight is the mechanism either way.
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
        // Without this the exchange would end before the body was sent, stamping Connection: close on
        // the very invitation to send it.
        if (HttpResponses.isInformational(msg)) {
            ctx.write(msg, promise);
            return;
        }
        if (msg instanceof HttpResponse response && isLastResponseOwedWhileDraining(ctx)) {
            HttpUtil.setKeepAlive(response.headers(), response.protocolVersion(), false);
        }
        // Completion, not invocation: shutdown waits for bytes to reach the client, so keying this on
        // invocation would let it abandon responses still unflushed. Safe here because a close fails
        // every outstanding write promise (AbstractUnsafe.close calls outboundBuffer.failFlushed), so
        // the listener always runs and inFlight always settles.
        //
        // unvoid() because addListener on a void promise throws, and the write must then carry the
        // unvoided promise or the listener would never be notified.
        ChannelPromise writePromise = promise;
        if (msg instanceof LastHttpContent) {
            writePromise = promise.unvoid();
            writePromise.addListener(_ -> connectionRegistry.exchangeFinished(ctx.channel()));
        }
        ctx.write(msg, writePromise);
    }

    private boolean isLastResponseOwedWhileDraining(ChannelHandlerContext ctx) {
        return connectionRegistry.isDraining() && connectionRegistry.inFlight(ctx.channel()) <= 1;
    }
}

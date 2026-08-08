package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.LastHttpContent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.RejectedExecutionException;

/**
 * Serves one HTTP exchange at a time per connection, so pipelined responses leave in request order
 * (issue #63). HTTP/1.1 identifies a response only by its position, and requests pipelined into one
 * TCP segment are decoded in a single event-loop turn, so without this gate they all reach
 * {@link HttpRequestHandler} on separate virtual threads and whichever finishes first writes first.
 * RFC 9112 §9.3.2 permits parallel processing only when every pipelined request has a safe method;
 * serving one at a time needs no method analysis. Sits below the aggregator so it gates whole
 * requests, and above the dispatcher so responses still pass back through. The aggregator's own
 * {@code 100 Continue} and {@code 413} originate above it and are never sequenced (issue #78).
 */
public class HttpPipeliningHandler extends ChannelDuplexHandler {

    /**
     * Requests read while an earlier exchange was still being served. Event loop only.
     */
    private final Deque<FullHttpRequest> pending = new ArrayDeque<>();

    /**
     * Set from the moment a request is passed on until its response has been handed to the socket —
     * write invoked, not flush completed. That distinction is the whole of {@link #write}.
     */
    private boolean serving;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (serving) {
            // Held, not forwarded, so this handler is its sole owner until it is passed on -- which is
            // why it needs no retain(), only the release in channelInactive.
            pending.addLast(request);
            return;
        }
        serving = true;
        ctx.fireChannelRead(request);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        // LastHttpContent rather than the dispatcher's return value, so a response written in parts
        // still ends its exchange at the end.
        if (!(msg instanceof LastHttpContent)) {
            ctx.write(msg, promise);
            return;
        }
        ctx.write(msg, promise);
        // Re-opened on write invocation, not on the promise: a peer whose receive window stays at zero
        // never completes it, so the gate would latch shut for ever and HttpReadTimeoutHandler, seeing
        // an exchange still unanswered, would never reclaim the connection (issue #76 review).
        //
        // Deferred rather than inline because the recursion is otherwise unbounded: HttpRequestHandler
        // fires exceptionCaught synchronously on this loop when its dispatch executor rejects, so a
        // burst answered in-loop re-enters this method once per queued request.
        try {
            ctx.executor().execute(() -> serveNext(ctx));
        } catch (RejectedExecutionException shuttingDown) {
            // Swallowed rather than propagated: the message has already been handed on, so letting this
            // out would fail the response promise for a write that did happen, firing the keep-alive and
            // drain completion listeners on a false failure.
        }
    }

    /**
     * Releases what is still queued: those requests were never passed on, so nothing downstream will
     * free them.
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        pending.forEach(FullHttpRequest::release);
        pending.clear();
        ctx.fireChannelInactive();
    }

    private void serveNext(ChannelHandlerContext ctx) {
        FullHttpRequest next = pending.pollFirst();
        if (next == null) {
            serving = false;
            return;
        }
        ctx.fireChannelRead(next);
    }
}

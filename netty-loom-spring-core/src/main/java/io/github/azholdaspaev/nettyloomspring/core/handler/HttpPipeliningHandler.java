package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.RejectedExecutionException;

/**
 * Serves one HTTP exchange at a time per connection, so pipelined responses leave in request order
 * (issue #63). HTTP/1.1 identifies a response only by its position, and requests pipelined into one
 * TCP segment are decoded in a single event-loop turn, so without this gate they all reach
 * {@link HttpRequestHandler} on separate virtual threads and whichever finishes first writes first.
 * RFC 9112 §9.3.2 permits parallel processing only when every pipelined request has a safe method;
 * serving one at a time needs no method analysis.
 *
 * <p>An exchange ends when its response has been written <em>and</em> its request is off the wire.
 * A handler that answers before the body has finished arriving would otherwise let the next request
 * overtake the tail of this one. Reads are withheld while anything is queued, so the backlog waits in
 * the socket rather than on the heap.
 */
public class HttpPipeliningHandler extends ChannelDuplexHandler {

    /** Parts read while an earlier exchange was still being served. Event loop only. */
    private final Deque<HttpObject> pending = new ArrayDeque<>();

    private boolean serving;

    /** The served request's {@link LastHttpContent} has not passed through yet. */
    private boolean bodyArriving;

    private boolean responseEnded;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof HttpObject part)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (part instanceof HttpRequest) {
            // Queued behind an empty queue too: an exchange that has just ended leaves serving false
            // before the queue drains, and a request arriving in that window would jump the ones
            // already waiting. Held, not forwarded, so this handler is its sole owner until it is
            // passed on -- which is why it needs no retain(), only the release in channelInactive.
            if (serving || !pending.isEmpty()) {
                pending.addLast(part);
                return;
            }
            begin(ctx, part);
            return;
        }
        if (!serving || !bodyArriving || !pending.isEmpty()) {
            pending.addLast(part);
            return;
        }
        forward(ctx, part);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        // LastHttpContent rather than the dispatcher's return value, so a response written in parts
        // still ends its exchange at the end.
        if (!(msg instanceof LastHttpContent) || HttpResponses.isInformational(msg)) {
            ctx.write(msg, promise);
            return;
        }
        ctx.write(msg, promise);
        // Ended on write invocation, not on the promise: a peer whose receive window stays at zero
        // never completes it, so the gate would latch shut for ever and HttpReadTimeoutHandler, seeing
        // an exchange still unanswered, would never reclaim the connection (issue #76 review).
        responseEnded = true;
        endExchangeIfSettled(ctx);
    }

    /**
     * Releases what is still queued: those parts were never passed on, so nothing downstream will
     * free them.
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        pending.forEach(ReferenceCountUtil::release);
        pending.clear();
        ctx.fireChannelInactive();
    }

    /** Withheld while anything is queued; {@link #serveNext} asks again once the queue drains. */
    @Override
    public void read(ChannelHandlerContext ctx) {
        if (pending.isEmpty()) {
            ctx.read();
        }
    }

    private void begin(ChannelHandlerContext ctx, HttpObject head) {
        serving = true;
        responseEnded = false;
        // A FullHttpRequest is its own terminator, so a pipeline that still aggregates has its body
        // off the wire on arrival.
        bodyArriving = !(head instanceof LastHttpContent);
        ctx.fireChannelRead(head);
        endExchangeIfSettled(ctx);
    }

    private void forward(ChannelHandlerContext ctx, HttpObject part) {
        if (part instanceof LastHttpContent) {
            bodyArriving = false;
        }
        ctx.fireChannelRead(part);
        endExchangeIfSettled(ctx);
    }

    private void endExchangeIfSettled(ChannelHandlerContext ctx) {
        if (!serving || bodyArriving || !responseEnded) {
            return;
        }
        serving = false;
        responseEnded = false;
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

    private void serveNext(ChannelHandlerContext ctx) {
        if (serving) {
            return;
        }
        if (!pending.isEmpty()) {
            begin(ctx, pending.pollFirst());
            while (serving && bodyArriving && !pending.isEmpty()
                && !(pending.peekFirst() instanceof HttpRequest)) {
                forward(ctx, pending.pollFirst());
            }
        }
        if (pending.isEmpty()) {
            ctx.read();
        }
    }
}

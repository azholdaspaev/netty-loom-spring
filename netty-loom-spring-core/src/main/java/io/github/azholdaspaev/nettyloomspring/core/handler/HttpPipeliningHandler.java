package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.LastHttpContent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Serves one HTTP exchange at a time per connection, so pipelined responses leave in request order
 * (issue #63).
 *
 * <p>HTTP/1.1 identifies a response only by its position: the first response on the wire is the answer
 * to the first request sent. Requests pipelined into one TCP segment are decoded in a single event-loop
 * turn — {@code ByteToMessageDecoder} drains the whole cumulation buffer, with no regard for auto-read —
 * so without this handler they all reach {@link HttpRequestHandler} before any of them has been served,
 * each on its own virtual thread. Whichever finishes first writes first, and the client then mismatches
 * every response after the first. RFC 9112 §9.3.2 permits parallel processing only when every pipelined
 * request has a safe method; serving one at a time needs no method analysis and is what the servlet
 * containers do.
 *
 * <p>Ordering is positional rather than correlated: because only one exchange is ever outstanding, the
 * next response to come back is necessarily the one owed, so no request-to-response identity is needed.
 * That is what lets the gate live here rather than inside the dispatcher.
 *
 * <p>{@code serving} and {@code pending} are touched only on the event loop. A response written from a
 * dispatch thread is hopped onto the loop by Netty before it reaches {@link #write}, and the promise
 * listener that re-opens the gate is notified there too.
 *
 * <p><strong>Placement is the contract.</strong> It belongs <em>below</em> the aggregator, so it gates
 * whole requests and so the aggregator's {@code 100 Continue} — written from that handler's own context,
 * and therefore travelling towards the head — never reaches it. Netty's own keep-alive and drain handlers
 * need an explicit interim-response exemption precisely because they sit above it; this one does not.
 * It belongs <em>above</em> the dispatcher, so requests are gated before dispatch while responses from
 * both the dispatcher and the tail exception handler still pass back through it.
 *
 * <p>Below the aggregator also means it <em>depends</em> on one. Nothing else in the pipeline produces a
 * {@link FullHttpRequest}, so in a hand-built pipeline assembled without an aggregator every message takes
 * the pass-through branch and this handler is silently inert rather than merely unused.
 *
 * <p>That placement is a trade, not a free win: the aggregator answers {@code Expect: 100-continue} and
 * rejects an oversized body with {@code 413} from its own context, so those responses originate above this
 * handler and are never sequenced by it. Both can still overtake a response being computed for an earlier
 * request — the ordering violation this handler otherwise exists to prevent (issue #78).
 */
public class HttpPipeliningHandler extends ChannelDuplexHandler {

    /** Requests read while an earlier exchange was still being served. Event loop only. */
    private final Deque<FullHttpRequest> pending = new ArrayDeque<>();

    /** Set from the moment a request is passed on until its response has been written. */
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
        // The exchange ends with the last of the response, which for a FullHttpResponse is the response
        // itself. Keying on LastHttpContent rather than on the dispatcher's return value keeps this
        // correct for a response written in parts.
        if (!(msg instanceof LastHttpContent)) {
            ctx.write(msg, promise);
            return;
        }
        ctx.write(msg, promise);
        // Re-opened once this response is queued, not once it has been flushed. Waiting for the write
        // promise reads as the safer choice and is not: a peer whose receive window stays at zero never
        // completes it, so the gate would latch shut for ever, the queued requests would never be
        // released or freed, and -- because the exchange stays unanswered -- HttpReadTimeoutHandler
        // would suspend its clock indefinitely and never reclaim the connection (issue #76 review).
        // Ordering is unaffected: this response is already in the outbound buffer, so the next one is
        // necessarily written behind it. Deferred to the next loop turn rather than called inline so an
        // inbound event is still never fired out of an outbound call.
        ctx.executor().execute(() -> serveNext(ctx));
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

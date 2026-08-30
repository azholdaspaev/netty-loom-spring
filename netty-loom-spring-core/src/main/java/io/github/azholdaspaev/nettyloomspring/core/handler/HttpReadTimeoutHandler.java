package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Ticker;

import java.util.concurrent.TimeUnit;

/**
 * Closes a connection the client has stopped making progress on, without counting the time spent
 * answering it (issue #76): one interval, measured from the previous response or from the accept.
 * Netty's {@link io.netty.handler.timeout.ReadTimeoutHandler} cannot express that — it stamps its
 * clock in {@code IdleStateHandler#channelReadComplete}, which fires before the dispatch has begun.
 * Counts a request in at its terminator rather than at its head, so a body that stops part way
 * still expires; above the pipelining gate, so the count stays a property of what the client has
 * delivered. A timeout of zero or less disables it, as it did the stock handler.
 */
public class HttpReadTimeoutHandler extends ChannelDuplexHandler {

    private final long timeoutNanos;

    /**
     * From the event loop, not {@link System#nanoTime()}: an {@code EmbeddedChannel}'s is drivable.
     */
    private Ticker ticker;

    private Future<?> timeoutTask;

    private long lastActivityNanos;

    /** A count, not a flag, because requests pipeline; negative while an answer outran its terminator. */
    private int unansweredRequests;

    /** A request head has passed with its terminator still to come. Event loop only. */
    private boolean requestArriving;

    /**
     * Both {@link #handlerAdded} and {@link #channelActive} reach {@link #initialize}; without this
     * the second would arm a second timer.
     */
    private boolean initialized;

    /**
     * Stops {@link #channelActive} arming a fresh timer after {@link #handlerRemoved} on a channel
     * that is still open, which the {@code isOpen()} check in {@link #run} would not catch.
     */
    private boolean destroyed;

    public HttpReadTimeoutHandler(long timeout, TimeUnit unit) {
        this.timeoutNanos = unit.toNanos(timeout);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ticker = ctx.executor().ticker();
        if (ctx.channel().isActive() && ctx.channel().isRegistered()) {
            // The arming path in production, not a fallback: register0 builds the pipeline in
            // invokeHandlerAddedIfNeeded before it fires channelActive, so an accepted child channel is
            // already connected here.
            initialize(ctx);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        initialize(ctx);
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        destroy();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        destroy();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            requestArriving = true;
        }
        if (msg instanceof LastHttpContent) {
            requestArriving = false;
            unansweredRequests++;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        // Counted out on write invocation, not on the promise: a peer whose receive window stays at zero
        // never completes it, so the clock would suspend for ever and the connection become immortal.
        // LastHttpContent rather than the dispatcher's return value keeps a response written in parts
        // restarting the clock at its end.
        if (msg instanceof LastHttpContent && !HttpResponses.isInformational(msg)) {
            requestAnswered();
        }
        ctx.write(msg, promise);
    }

    /**
     * Counts against the request still being delivered when the answer outran its terminator; one
     * with nothing in flight at all is ignored, or a count below zero leaves a busy connection idle.
     */
    private void requestAnswered() {
        if (unansweredRequests > 0 || requestArriving) {
            unansweredRequests--;
        }
        lastActivityNanos = ticker.nanoTime();
    }

    private void initialize(ChannelHandlerContext ctx) {
        if (initialized || destroyed || timeoutNanos <= 0) {
            return;
        }
        initialized = true;
        lastActivityNanos = ticker.nanoTime();
        schedule(ctx, timeoutNanos);
    }

    private void destroy() {
        destroyed = true;
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
    }

    private void schedule(ChannelHandlerContext ctx, long delayNanos) {
        timeoutTask = ctx.executor().schedule(() -> run(ctx), delayNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Re-arms for a whole interval mid-exchange and reschedules for the remainder once the response
     * is out, so the connection carries one timer and no per-request scheduling.
     */
    private void run(ChannelHandlerContext ctx) {
        if (destroyed || !ctx.channel().isOpen()) {
            return;
        }
        if (unansweredRequests > 0) {
            schedule(ctx, timeoutNanos);
            return;
        }
        long remaining = timeoutNanos - (ticker.nanoTime() - lastActivityNanos);
        if (remaining > 0) {
            schedule(ctx, remaining);
            return;
        }
        readTimedOut(ctx);
    }

    /**
     * Netty's own exception, so {@link HttpExceptionHandler} keeps mapping it to a close with no
     * response written; closes here too, for a pipeline assembled without that tail handler.
     */
    private void readTimedOut(ChannelHandlerContext ctx) {
        ctx.fireExceptionCaught(ReadTimeoutException.INSTANCE);
        ctx.close();
    }
}

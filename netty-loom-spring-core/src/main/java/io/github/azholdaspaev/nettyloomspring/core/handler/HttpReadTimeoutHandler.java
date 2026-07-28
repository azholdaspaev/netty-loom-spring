package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Ticker;

import java.util.concurrent.TimeUnit;

/**
 * Closes a connection the client has stopped making progress on, without counting the time we spend
 * answering it (issue #76).
 *
 * <p>The timeout measures how long we wait on the <em>client</em>. It is a <em>single</em> interval,
 * measured from the previous response (or from the connection being accepted), covering idle time and
 * request delivery <em>together</em> — not one interval for each. A connection that idles most of the
 * interval in a client pool therefore has only the remainder left to deliver its next request, which is
 * the cost of measuring requests rather than bytes; see the trade-offs in README. Time spent dispatching
 * is exempt, so a handler slower than the timeout still gets to answer. That is the parity Tomcat's
 * {@code connectionTimeout} offers — it governs waiting for the request line and headers and does not
 * run during servlet execution — and it is what Netty's stock
 * {@link io.netty.handler.timeout.ReadTimeoutHandler} cannot express: it is an
 * {@code IdleStateHandler} armed on reader-idle alone, and {@code IdleStateHandler} stamps its clock in
 * {@code channelReadComplete} — which fires before the dispatch has even begun, because
 * {@link HttpRequestHandler} hands off to a virtual thread and returns. The clock then ran free for the
 * whole dispatch and closed the connection with no response written.
 *
 * <p><strong>Placement is the contract.</strong> It belongs <em>below</em> the aggregator, so it sees whole
 * requests rather than bytes: that is what makes the interval a deadline to deliver a request rather than
 * a byte-level idle timeout, and it closes a client dribbling a header a byte at a time — which a
 * byte-level clock keeps alive indefinitely. It belongs <em>above</em> the pipelining gate so that the
 * count means "complete requests the client has delivered and we have not answered" — a property of the
 * client, which is what this measures — rather than "requests the gate has currently released", which
 * would tie the timeout to that handler's scheduling. Correctness does not turn on the side, but not
 * because the count behaves the same: below the gate a response reaches this handler <em>before</em>
 * that one, so the count drops to zero after every pipelined response and only returns when the next
 * request is released. It is safe there because {@link #requestAnswered} refreshes the clock in the same
 * call, so the connection is never read as idle during the dip.
 *
 * <p>It does not reuse {@link HttpConnectionRegistry}'s in-flight count, which tracks the same idea one
 * step further out. That window opens at the <em>head</em> of a request, above the aggregator, so
 * suspending on it would exempt a client that sends complete headers and then stalls mid-body — for
 * ever, reopening the very hole this closes.
 *
 * <p>Below the aggregator also means it never sees that handler's own {@code 100 Continue} or {@code 413}
 * — both are written from the aggregator's context, towards the head. So, like
 * {@link HttpPipeliningHandler} and unlike {@link HttpDrainHandler}, it needs no interim-response
 * exemption. The trade is that an {@code Expect: 100-continue} upload is not exempt from the deadline
 * either; the body must still arrive within the interval.
 *
 * <p>The exchange is counted out when the response is <em>handed to</em> the socket, not when the bytes
 * are accepted by it. Keying on write completion is the obvious choice and is wrong: a peer whose receive
 * window stays at zero leaves the promise uncompleted for ever — Netty parks the message in
 * {@code ChannelOutboundBuffer} and the high-water mark only flips writability, it never fails the write
 * — so the suspension would never end and the connection would become immortal. The stock head-mounted
 * handler reclaimed that case because it keyed on reads, and losing it would be a regression, not merely
 * an undocumented gap.
 *
 * <p><strong>Nothing below this handler may latch on write completion either.</strong>
 * {@link HttpDrainHandler} and {@link HttpPipeliningHandler} both key on it, which is right for what they
 * do — but this handler's close is the <em>only</em> thing that can reclaim a connection those two have
 * latched, and a gate that never re-opens leaves an exchange permanently unanswered, which suspends this
 * clock for ever. That is why {@link HttpPipeliningHandler} re-opens on write invocation rather than on
 * the promise: a pipelined burst against a peer that stops reading would otherwise pin the count above
 * zero, strand every queued request, and make the connection immortal.
 *
 * <p>Every field is touched only on the event loop. A response written from a dispatch thread is hopped
 * onto the loop by Netty before it reaches {@link #write}, and the timeout task runs there by
 * construction.
 *
 * <p>A timeout of zero or less disables the handler entirely, matching what the stock handler did with a
 * non-positive timeout.
 */
public class HttpReadTimeoutHandler extends ChannelDuplexHandler {

    private final long timeoutNanos;

    /**
     * Taken from the event loop rather than read from {@link System#nanoTime()} directly, the way
     * {@code IdleStateHandler} does it — an {@code EmbeddedChannel} supplies a ticker the test drives by
     * hand, which is what lets the timeouts be asserted exactly instead of slept for.
     */
    private Ticker ticker;

    private Future<?> timeoutTask;

    /** When the client last finished making progress: connection established, or an exchange answered. */
    private long lastActivityNanos;

    /**
     * Complete requests read but not yet answered. A count, not a flag, because requests pipeline.
     *
     * <p>Deliberately not called {@code inFlight}: {@link HttpConnectionRegistry} uses that name for the
     * wider window opening at the head of a request, and the two are not interchangeable.
     */
    private int unansweredRequests;

    /**
     * Load-bearing rather than defensive: in production both {@link #handlerAdded} and
     * {@link #channelActive} reach {@link #initialize}, and without this the second would arm a second
     * timer.
     */
    private boolean initialized;

    /** Redundant with the {@code isOpen()} check in {@link #run}; kept so a cancelled task cannot race. */
    private boolean destroyed;

    public HttpReadTimeoutHandler(long timeout, TimeUnit unit) {
        this.timeoutNanos = unit.toNanos(timeout);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ticker = ctx.executor().ticker();
        if (ctx.channel().isActive() && ctx.channel().isRegistered()) {
            // This is the arming path in production, not a fallback: register0 sets registered, then runs
            // invokeHandlerAddedIfNeeded -- where the pipeline is built -- and only then fires
            // channelRegistered and channelActive. An accepted child channel is connected by that point,
            // so the timer starts here and the channelActive path below no-ops on the initialized guard.
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
        if (msg instanceof FullHttpRequest) {
            unansweredRequests++;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        // The exchange ends with the last of the response, which for a FullHttpResponse is the response
        // itself. Keying on LastHttpContent rather than on the dispatcher's return value keeps this
        // correct for a response written in parts: a streamed response restarts the clock at its end, not
        // at its head.
        if (msg instanceof LastHttpContent) {
            requestAnswered();
        }
        ctx.write(msg, promise);
    }

    /**
     * Ignores a response with nothing outstanding rather than going negative, for the reason
     * {@link HttpConnectionRegistry#exchangeFinished} gives at length: a count below zero leaves a busy
     * connection looking idle.
     */
    private void requestAnswered() {
        if (unansweredRequests > 0) {
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
     * Re-arming for a whole interval while an exchange is in flight costs no precision: the task fires
     * again once the response is out, finds the clock restarted, and reschedules for the remainder — so
     * the close still lands exactly one interval after the response. It does mean the connection carries
     * one timer and no per-request scheduling at all.
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
     * Fires Netty's own exception so {@link HttpExceptionHandler} keeps mapping it to a close with no
     * response written, and closes here as well so the handler is still correct in a pipeline assembled
     * without that tail handler.
     *
     * <p>Needs no already-fired guard, unlike Netty's {@code ReadTimeoutHandler}: that one extends
     * {@code IdleStateHandler}, whose task re-arms itself before every notification, whereas this is the
     * one branch of {@link #run} that does not reschedule. A second firing is unreachable.
     */
    private void readTimedOut(ChannelHandlerContext ctx) {
        ctx.fireExceptionCaught(ReadTimeoutException.INSTANCE);
        ctx.close();
    }
}

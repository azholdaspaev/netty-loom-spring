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
 * <p>The timeout measures how long we wait on the <em>client</em>: it allows one interval to deliver a
 * complete request, and one interval of sitting idle between exchanges. Time spent dispatching is
 * exempt, so a handler slower than the timeout still gets to answer. That is the parity Tomcat's
 * {@code connectionTimeout} and Jetty's {@code idleTimeout} offer, and it is what Netty's stock
 * {@link io.netty.handler.timeout.ReadTimeoutHandler} cannot express: it is an
 * {@code IdleStateHandler} armed on reader-idle alone, and {@code IdleStateHandler} stamps its clock in
 * {@code channelReadComplete} — which fires before the dispatch has even begun, because
 * {@link HttpRequestHandler} hands off to a virtual thread and returns. The clock then ran free for the
 * whole dispatch and closed the connection with no response written.
 *
 * <p><strong>Placement is the contract.</strong> It belongs <em>below</em> the aggregator, so it sees whole
 * requests rather than bytes: that is what makes the interval a deadline to deliver a request rather than
 * a byte-level idle timeout, and it closes a client dribbling a header a byte at a time — which a
 * byte-level clock keeps alive indefinitely. It belongs <em>above</em> the pipelining gate, because
 * {@link HttpPipeliningHandler} releases a queued request with {@code fireChannelRead} from its own
 * context, travelling towards the tail: below it, those requests would never be seen and a pipelined
 * burst would be undercounted.
 *
 * <p>Below the aggregator also means it never sees that handler's own {@code 100 Continue} or {@code 413}
 * — both are written from the aggregator's context, towards the head. So, like
 * {@link HttpPipeliningHandler} and unlike {@link HttpDrainHandler}, it needs no interim-response
 * exemption. The trade is that an {@code Expect: 100-continue} upload is not exempt from the deadline
 * either; the body must still arrive within the interval.
 *
 * <p>Every field is touched only on the event loop. A response written from a dispatch thread is hopped
 * onto the loop by Netty before it reaches {@link #write}, the promise listener is notified there too,
 * and the timeout task runs there by construction.
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

    /** Complete requests read but not yet answered. A count, not a flag, because requests pipeline. */
    private int outstanding;

    private boolean initialized;
    private boolean destroyed;

    public HttpReadTimeoutHandler(long timeout, TimeUnit unit) {
        this.timeoutNanos = unit.toNanos(timeout);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ticker = ctx.executor().ticker();
        if (ctx.channel().isActive() && ctx.channel().isRegistered()) {
            // Added to a channel that is already up -- channelActive will not come, so start here.
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
            outstanding++;
        }
        ctx.fireChannelRead(msg);
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
        // unvoid() because addListener on a void promise throws, and the write must then carry the
        // unvoided promise or the listener would never be notified. Counting the exchange out on write
        // completion rather than inline here also keeps a response still going out over a slow socket
        // from being cut short by its own timeout.
        ChannelPromise writePromise = promise.unvoid();
        writePromise.addListener(_ -> exchangeFinished());
        ctx.write(msg, writePromise);
    }

    /**
     * Ignores a response with nothing outstanding rather than going negative, for the reason
     * {@link HttpConnectionRegistry#exchangeFinished} gives at length: a count below zero leaves a busy
     * connection looking idle.
     */
    private void exchangeFinished() {
        if (outstanding > 0) {
            outstanding--;
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
        if (outstanding > 0) {
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

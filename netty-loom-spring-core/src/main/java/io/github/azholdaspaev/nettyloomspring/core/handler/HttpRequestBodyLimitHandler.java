package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DuplexChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.PromiseNotifier;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Bounds the request body, and answers the expectation that negotiates it — {@code 100 Continue},
 * {@code 417} and the {@code 413} a declared length already settles (issues #51, #46). Both were
 * {@code HttpObjectAggregator}'s, and its ordering is kept: an unsupported expectation is refused
 * first, and a declared length over the limit is refused whatever the client expected — which Netty
 * split across {@code HttpObjectAggregator.continueResponse} for a request carrying an expectation
 * and the {@code isContentLengthInvalid} branch of {@code MessageAggregator.decode} for one without.
 *
 * <p>Both refusals end the connection, where {@code MessageAggregator.handleOversizedMessage} kept
 * a keep-alive one and discarded the body itself. They end it in stages, as RFC 9112 §9.6 advises,
 * because closing over unread request bytes sends a reset that destroys the refusal in the client's
 * buffer: the output is shut once the refusal is flushed, and what the client still sends, the
 * refused body and whatever it pipelined behind it, is discarded until it hangs up, more than
 * {@code maxSwallowBytes} arrives or {@code swallowTimeout} passes. Tomcat bounds the same drain
 * with {@code maxSwallowSize}.
 */
public class HttpRequestBodyLimitHandler extends ChannelDuplexHandler {

    private final long maxBodyBytes;

    private final long maxSwallowBytes;

    private final long swallowTimeoutNanos;

    private long received;

    /** The rest of this request's body is being dropped. Event loop only. */
    private boolean refused;

    /**
     * Set before the refusal is written, since HttpServerKeepAliveHandler's close arrives inside that
     * write. Never cleared: the connection ends with it. Event loop only.
     */
    private boolean closing;

    /** The drain has ended, at a bound or with the client gone, so closes pass through. Event loop only. */
    private boolean drained;

    private long swallowed;

    private ChannelPromise heldClose;

    private Future<?> swallowDeadline;

    public HttpRequestBodyLimitHandler(long maxBodyBytes, long maxSwallowBytes, Duration swallowTimeout) {
        this.maxBodyBytes = maxBodyBytes;
        this.maxSwallowBytes = maxSwallowBytes;
        this.swallowTimeoutNanos = TimeUnit.NANOSECONDS.convert(swallowTimeout);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (closing) {
            swallow(ctx, msg);
            return;
        }
        if (msg instanceof HttpRequest request) {
            received = 0;
            refused = false;
            if (refuseOrNegotiate(ctx, request)) {
                return;
            }
            ctx.fireChannelRead(msg);
            return;
        }
        if (msg instanceof HttpContent content) {
            if (refused) {
                content.release();
                return;
            }
            received += content.content().readableBytes();
            if (received > maxBodyBytes) {
                refused = true;
                content.release();
                /*
                 * The status is left to HttpExceptionHandler's mapping rather than written here, so a
                 * body refused mid-dispatch cannot overtake a response already going out (issue #78).
                 */
                ctx.fireExceptionCaught(new TooLongFrameException(
                    "Request body exceeded " + maxBodyBytes + " bytes"));
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    /** Holds a close that would cut off the refused body, rather than passing it on. */
    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        if (!closing || drained) {
            ctx.close(promise);
            return;
        }
        if (heldClose != null) {
            heldClose.addListener(new PromiseNotifier<>(promise));
            return;
        }
        heldClose = promise;
        if (ctx.channel() instanceof DuplexChannel duplex) {
            duplex.shutdownOutput();
        }
    }

    /** Asks for the drain's next read itself, since HttpRequestHandler withholds reads while an earlier body is full. */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        if (closing) {
            ctx.read();
        }
        ctx.fireChannelReadComplete();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (closing) {
            endDrain(ctx);
        }
        ctx.fireChannelInactive();
    }

    private void swallow(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpContent content) {
            swallowed += content.content().readableBytes();
        }
        ReferenceCountUtil.release(msg);
        if (swallowed > maxSwallowBytes) {
            endDrain(ctx);
        }
    }

    private void endDrain(ChannelHandlerContext ctx) {
        if (drained) {
            return;
        }
        drained = true;
        swallowDeadline.cancel(false);
        // Without waiting for the refusal's own close: a refusal stuck unsent must not lift the bounds.
        ChannelPromise promise = heldClose == null ? ctx.newPromise() : heldClose;
        heldClose = null;
        ctx.close(promise);
    }

    /** Refuses what cannot be served and answers {@code Expect}, reporting whether that ends the exchange. */
    private boolean refuseOrNegotiate(ChannelHandlerContext ctx, HttpRequest request) {
        if (isUnsupportedExpectation(request)) {
            return refuse(ctx, request, HttpResponseStatus.EXPECTATION_FAILED);
        }
        if (HttpUtil.getContentLength(request, -1L) > maxBodyBytes) {
            return refuse(ctx, request, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
        }
        if (!HttpUtil.is100ContinueExpected(request)) {
            return false;
        }
        request.headers().remove(HttpHeaderNames.EXPECT);
        ctx.writeAndFlush(emptyResponse(HttpResponseStatus.CONTINUE));
        return false;
    }

    private boolean refuse(ChannelHandlerContext ctx, HttpRequest request, HttpResponseStatus status) {
        closing = true;
        swallowDeadline = ctx.executor().schedule(() -> endDrain(ctx), swallowTimeoutNanos, TimeUnit.NANOSECONDS);
        ReferenceCountUtil.release(request);
        FullHttpResponse rejection = emptyResponse(status);
        /*
         * Netty's HttpServerKeepAliveHandler stamps nothing on a response of self-defined length, so
         * without this a pooling client reuses a socket this refusal is about to close (RFC 9112 §9.6).
         */
        rejection.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(rejection).addListener(ChannelFutureListener.CLOSE);
        return true;
    }

    /**
     * {@code HttpUtil.isUnsupportedExpectation} is package-private to Netty: any {@code Expect} other
     * than {@code 100-continue}, and only on HTTP/1.1 or later (RFC 9110 §10.1.1).
     */
    private static boolean isUnsupportedExpectation(HttpRequest request) {
        if (request.protocolVersion().compareTo(HttpVersion.HTTP_1_1) < 0) {
            return false;
        }
        String expectation = request.headers().get(HttpHeaderNames.EXPECT);
        return expectation != null
            && !HttpHeaderValues.CONTINUE.toString().equalsIgnoreCase(expectation);
    }

    private static FullHttpResponse emptyResponse(HttpResponseStatus status) {
        FullHttpResponse response =
            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        return response;
    }
}

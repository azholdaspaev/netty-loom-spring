package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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

/**
 * Bounds the request body, and answers the expectation that negotiates it — {@code 100 Continue},
 * {@code 417} and the {@code 413} a declared length already settles (issues #51, #46). Both were
 * {@code HttpObjectAggregator}'s, and its ordering is kept: an unsupported expectation is refused
 * first, and a declared length over the limit is refused whatever the client expected — which Netty
 * split across {@code HttpObjectAggregator.continueResponse} for a request carrying an expectation
 * and the {@code isContentLengthInvalid} branch of {@code MessageAggregator.decode} for one without.
 *
 * <p>Both refusals close, where {@code MessageAggregator.handleOversizedMessage} closed only a full
 * message, an auto-read-off channel or one already not keep-alive and otherwise discarded the body
 * itself: with nothing aggregating, no handler below would consume what the client sends anyway.
 */
public class HttpRequestBodyLimitHandler extends ChannelInboundHandlerAdapter {

    private final long maxBodyBytes;

    private long received;

    /** The rest of this request's body is being dropped. Event loop only. */
    private boolean refused;

    public HttpRequestBodyLimitHandler(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
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
                // The status is left to HttpExceptionHandler's mapping rather than written here, so a
                // body refused mid-dispatch cannot overtake a response already going out (issue #78).
                ctx.fireExceptionCaught(new TooLongFrameException(
                    "Request body exceeded " + maxBodyBytes + " bytes"));
                return;
            }
        }
        ctx.fireChannelRead(msg);
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
        refused = true;
        ReferenceCountUtil.release(request);
        FullHttpResponse rejection = emptyResponse(status);
        // Netty's HttpServerKeepAliveHandler stamps nothing on a response of self-defined length, so
        // without this a pooling client reuses the socket the listener below closes (RFC 9112 §9.6).
        rejection.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(rejection).addListener(ChannelFutureListener.CLOSE);
        return true;
    }

    /**
     * {@code HttpUtil.isUnsupportedExpectation} is package-private to Netty, so its rule is restated:
     * any {@code Expect} other than {@code 100-continue}, and only on HTTP/1.1 or later, since RFC 9110
     * §10.1.1 has a server ignore the expectation in an HTTP/1.0 request.
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

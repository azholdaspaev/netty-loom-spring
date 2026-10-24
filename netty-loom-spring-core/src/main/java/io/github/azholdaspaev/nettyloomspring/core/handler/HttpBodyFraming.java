package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

/** Settled on the connection, so no dispatcher has to know the version it turns on. */
final class HttpBodyFraming {

    private HttpBodyFraming() {
    }

    static void applyTo(HttpRequest request, HttpResponse response) {
        // A dispatcher's own coding could reach HTTP/1.0 or sit beside Content-Length (RFC 9112 6.1, 6.2).
        boolean dispatcherFramed = response.headers().contains(HttpHeaderNames.TRANSFER_ENCODING);
        response.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
        if (dispatcherFramed) {
            // Transfer-Encoding overrides Content-Length (RFC 9112 6.3), so that length never framed the message.
            response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        }
        // HttpObjectEncoder writes trailers only in a chunked body.
        if (carriesTrailers(request, response)) {
            HttpUtil.setTransferEncodingChunked(response, true);
            return;
        }
        if (HttpUtil.isContentLengthSet(response)) {
            return;
        }
        if (response instanceof LastHttpContent whole) {
            /*
             * A HEAD response's length must be the one a GET would send (RFC 9110 8.6): an empty one may
             * not hold that body, while Servlet 6.1 HttpServlet.doHead runs doGet and buffers it.
             */
            boolean emptyHead = HttpMethod.HEAD.equals(request.method()) && !whole.content().isReadable();
            if (!emptyHead) {
                HttpUtil.setContentLength(response, whole.content().readableBytes());
                return;
            }
        }
        /*
         * Netty repairs most of this set and not 304: sanitizeHeadersBeforeEncode skips it while
         * isContentAlwaysEmpty still drops its body and terminator. Declared empty rather than left
         * bare because isSelfDefinedMessageLength covers 1xx and 204 but not 304 or 205, so a bare
         * head has HttpServerKeepAliveHandler close after every conditional GET.
         */
        if (HttpResponses.carriesNoBody(response.status())) {
            HttpUtil.setContentLength(response, 0);
            return;
        }
        /*
         * An HTTP/1.0 client reads chunk-size lines as body content, so its response is left for
         * the close to delimit; HttpServerKeepAliveHandler then stamps Connection: close itself.
         */
        if (HttpVersion.HTTP_1_0.equals(request.protocolVersion())) {
            return;
        }
        HttpUtil.setTransferEncodingChunked(response, true);
    }

    private static boolean carriesTrailers(HttpRequest request, HttpResponse response) {
        return response instanceof LastHttpContent whole
            && !whole.trailingHeaders().isEmpty()
            && !HttpVersion.HTTP_1_0.equals(request.protocolVersion());
    }
}

package io.github.azholdaspaev.nettyloom.core.http;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;

public class DefaultNettyHttpResponseConverter implements NettyHttpResponseConverter {

    @Override
    public FullHttpResponse convert(NettyHttpResponse msg) {
        byte[] body = msg.body() != null ? msg.body() : new byte[0];

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(msg.statusCode()), Unpooled.wrappedBuffer(body));

        if (msg.headers() != null) {
            for (var entry : msg.headers().entrySet()) {
                for (String value : entry.getValue()) {
                    response.headers().add(entry.getKey(), value);
                }
            }
        }

        response.headers()
                .set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        return response;
    }
}

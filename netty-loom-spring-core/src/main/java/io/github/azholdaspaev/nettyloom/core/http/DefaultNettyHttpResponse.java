package io.github.azholdaspaev.nettyloom.core.http;

import java.util.List;
import java.util.Map;

public class DefaultNettyHttpResponse implements NettyHttpResponse {

    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    private DefaultNettyHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public Map<String, List<String>> headers() {
        return headers;
    }

    @Override
    public byte[] body() {
        return body;
    }

    public static DefaultNettyHttpResponseBuilder builder() {
        return new DefaultNettyHttpResponseBuilder();
    }

    public static class DefaultNettyHttpResponseBuilder {
        private int statusCode = 200;
        private Map<String, List<String>> headers = Map.of();
        private byte[] body = new byte[0];

        public DefaultNettyHttpResponseBuilder statusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        public DefaultNettyHttpResponseBuilder headers(Map<String, List<String>> headers) {
            this.headers = headers;
            return this;
        }

        public DefaultNettyHttpResponseBuilder body(byte[] body) {
            this.body = body;
            return this;
        }

        public DefaultNettyHttpResponse build() {
            return new DefaultNettyHttpResponse(statusCode, headers, body);
        }
    }
}

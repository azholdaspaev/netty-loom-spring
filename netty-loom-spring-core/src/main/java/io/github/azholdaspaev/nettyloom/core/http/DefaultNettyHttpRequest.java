package io.github.azholdaspaev.nettyloom.core.http;

import java.util.List;
import java.util.Map;

public class DefaultNettyHttpRequest implements NettyHttpRequest {

    private final HttpMethod method;
    private final String uri;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    private DefaultNettyHttpRequest(HttpMethod method, String uri, Map<String, List<String>> headers, byte[] body) {
        this.method = method;
        this.uri = uri;
        this.headers = headers;
        this.body = body;
    }

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public Map<String, List<String>> headers() {
        return headers;
    }

    @Override
    public byte[] body() {
        return body;
    }

    public static DefaultNettyHttpRequestBuilder builder() {
        return new DefaultNettyHttpRequestBuilder();
    }

    public static class DefaultNettyHttpRequestBuilder {
        private HttpMethod method = HttpMethod.GET;
        private String uri = "/";
        private Map<String, List<String>> headers = Map.of();
        private byte[] body = new byte[0];

        public DefaultNettyHttpRequestBuilder method(HttpMethod method) {
            this.method = method;
            return this;
        }

        public DefaultNettyHttpRequestBuilder uri(String uri) {
            this.uri = uri;
            return this;
        }

        public DefaultNettyHttpRequestBuilder headers(Map<String, List<String>> headers) {
            this.headers = headers;
            return this;
        }

        public DefaultNettyHttpRequestBuilder body(byte[] body) {
            this.body = body;
            return this;
        }

        public DefaultNettyHttpRequest build() {
            return new DefaultNettyHttpRequest(method, uri, headers, body);
        }
    }
}

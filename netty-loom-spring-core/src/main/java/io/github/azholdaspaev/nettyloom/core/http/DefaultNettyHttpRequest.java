package io.github.azholdaspaev.nettyloom.core.http;

public class DefaultNettyHttpRequest implements NettyHttpRequest {
    private final HttpMethod method;
    private final String uri;

    public DefaultNettyHttpRequest(HttpMethod method, String uri) {
        this.method = method;
        this.uri = uri;
    }
}

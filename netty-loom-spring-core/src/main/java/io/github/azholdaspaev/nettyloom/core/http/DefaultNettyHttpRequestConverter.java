package io.github.azholdaspaev.nettyloom.core.http;

import io.netty.handler.codec.http.FullHttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DefaultNettyHttpRequestConverter implements NettyHttpRequestConverter {

    @Override
    public NettyHttpRequest convert(FullHttpRequest msg) {
        HttpMethod method = HttpMethod.valueOf(msg.method().name());

        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : msg.headers()) {
            headers.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
        }

        byte[] body = new byte[msg.content().readableBytes()];
        msg.content().readBytes(body);

        return DefaultNettyHttpRequest.builder()
                .method(method)
                .uri(msg.uri())
                .headers(headers)
                .body(body)
                .build();
    }
}

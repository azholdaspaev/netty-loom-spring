package io.github.azholdaspaev.nettyloom.core.http;

import java.util.List;
import java.util.Map;

public interface NettyHttpResponse {

    int statusCode();

    byte[] body();

    Map<String, List<String>> headers();
}

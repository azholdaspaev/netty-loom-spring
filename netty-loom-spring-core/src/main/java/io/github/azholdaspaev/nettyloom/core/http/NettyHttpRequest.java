package io.github.azholdaspaev.nettyloom.core.http;

import java.util.List;
import java.util.Map;

public interface NettyHttpRequest {

    HttpMethod method();

    String uri();

    byte[] body();

    Map<String, List<String>> headers();
}

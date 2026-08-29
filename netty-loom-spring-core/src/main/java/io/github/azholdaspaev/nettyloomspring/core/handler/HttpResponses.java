package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpStatusClass;

final class HttpResponses {

    private HttpResponses() {
    }

    /**
     * A 1xx is an interim answer, not the end of an exchange. {@link HttpRequestBodyLimitHandler}
     * writes {@code 100 Continue} as a {@code FullHttpResponse} — both an {@code HttpResponse} and a
     * {@code LastHttpContent} — so every handler that ends an exchange on the latter has to ask.
     */
    static boolean isInformational(Object msg) {
        return msg instanceof HttpResponse response
            && response.status().codeClass() == HttpStatusClass.INFORMATIONAL;
    }
}

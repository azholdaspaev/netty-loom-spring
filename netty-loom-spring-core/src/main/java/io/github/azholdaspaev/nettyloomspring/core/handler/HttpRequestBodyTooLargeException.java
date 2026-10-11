package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.handler.codec.TooLongFrameException;

/**
 * The body cap's own type rather than a plain {@code TooLongFrameException}, because a trailer block
 * past the codec's limit arrives as one too, and only the body cap leaves the connection usable.
 */
final class HttpRequestBodyTooLargeException extends TooLongFrameException {

    HttpRequestBodyTooLargeException(long maxBodyBytes) {
        super("Request body exceeded " + maxBodyBytes + " bytes");
    }
}

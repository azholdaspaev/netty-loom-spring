package io.github.azholdaspaev.nettyloomspring.core.handler;

/**
 * A failure the application owns, answered 500 whatever its cause, as Tomcat's StandardWrapperValve
 * answers any {@code Throwable} out of the filter chain.
 */
final class HttpDispatchException extends RuntimeException {

    HttpDispatchException(Throwable cause) {
        super(cause);
    }
}

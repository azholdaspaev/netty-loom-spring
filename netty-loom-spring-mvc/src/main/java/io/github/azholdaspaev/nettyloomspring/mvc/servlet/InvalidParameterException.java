package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

/**
 * A parameter the client encoded badly, answered 400 as Tomcat answers its own
 * {@code InvalidParameterException} ({@code StandardWrapperValve}). An {@code IllegalStateException}
 * because Servlet 6.1 {@code ServletRequest.getParameter} names that type for invalid %nn encoding.
 */
final class InvalidParameterException extends IllegalStateException {

    InvalidParameterException(IllegalArgumentException malformedEscape) {
        super(malformedEscape.getMessage(), malformedEscape);
    }
}

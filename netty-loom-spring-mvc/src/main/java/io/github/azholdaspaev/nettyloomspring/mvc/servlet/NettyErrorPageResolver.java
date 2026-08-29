package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

/**
 * The container's registered error pages, reduced to the one lookup a failed request needs (issue #38).
 * Expressed in Jakarta terms rather than as Boot's {@code ErrorPage} because this module carries no
 * Spring Boot dependency; the starter adapts the registrations to it.
 */
@FunctionalInterface
public interface NettyErrorPageResolver {

    /** No error pages registered, so every failure is answered by its bare status. */
    NettyErrorPageResolver NO_PAGES = (status, failure) -> null;

    /**
     * The context-relative path of the page answering a failure, or {@code null} for none. {@code failure}
     * is already unwrapped to its root cause, and {@code status} is already the one the response will
     * carry.
     */
    String resolve(int status, Throwable failure);
}

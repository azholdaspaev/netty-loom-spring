package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

/**
 * The container's registered error pages, reduced to the one lookup a failed request needs (issue #38).
 * Expressed in Jakarta terms rather than as Boot's {@code ErrorPage} because this module carries no
 * Spring Boot dependency; the starter adapts the registrations to it.
 */
@FunctionalInterface
public interface NettyErrorPageResolver {

    /** No error pages registered, so every failure is answered by its bare status. */
    NettyErrorPageResolver NO_PAGES = (status, failure, rootCause) -> null;

    /**
     * The context-relative path of the page answering a failure, or {@code null} for none. A page
     * registered for {@code failure} as thrown wins over one registered for its {@code rootCause}, and
     * both over {@code status} -- the order Tomcat's {@code StandardHostValve.throwable} resolves in.
     * The two throwables are the same object where nothing wrapped the failure, and both are
     * {@code null} where there was none. {@code status} is already the one the response will carry.
     */
    String resolve(int status, Throwable failure, Throwable rootCause);
}

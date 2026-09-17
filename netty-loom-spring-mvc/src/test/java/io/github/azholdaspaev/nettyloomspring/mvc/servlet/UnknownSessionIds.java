package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

/**
 * Session ids well-formed as {@link NettySessionManager#create()} mints them -- 32 uppercase hex --
 * but naming no session. Container-shaped rather than a short marker such as {@code "DEADBEEF"}
 * (issue #97).
 */
final class UnknownSessionIds {

    static final String UNKNOWN_SESSION_ID = "0123456789ABCDEF0123456789ABCDEF";

    static final String OTHER_UNKNOWN_SESSION_ID = "FEDCBA9876543210FEDCBA9876543210";

    private UnknownSessionIds() {
    }
}

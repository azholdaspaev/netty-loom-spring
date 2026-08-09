package io.github.azholdaspaev.nettyloomspring.autoconfigure.support;

/**
 * Test helpers for walking an exception cause chain. Centralizes the
 * {@code for (cause = t; cause != null; cause = cause.getCause())} idiom so assertions about wrapped
 * startup failures do not each re-implement it. Spring's {@code NestedExceptionUtils} only exposes
 * root/most-specific-cause accessors, not a "chain contains this message/type" search, so it does not
 * cover these needs.
 */
public final class ThrowableChains {

    private ThrowableChains() {
    }

    public static boolean chainMentions(Throwable throwable, String needle) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public static <T extends Throwable> T findInChain(Throwable throwable, Class<T> type) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }
}

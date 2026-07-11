package io.github.azholdaspaev.nettyloomspring.autoconfigure.support;

import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.net.BindException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrowableChainsTest {

    @Test
    void chainMentionsFindsNeedleInWrapperCause() {
        Throwable top = new RuntimeException("outer", new IllegalStateException("mentions issue #16"));

        assertTrue(ThrowableChains.chainMentions(top, "issue #16"));
        assertFalse(ThrowableChains.chainMentions(top, "absent"));
    }

    @Test
    void chainMentionsToleratesNullMessagesInChain() {
        Throwable top = new RuntimeException(new IllegalStateException("issue #16"));

        assertTrue(ThrowableChains.chainMentions(top, "issue #16"));
    }

    @Test
    void findInChainReturnsFirstMatchingType() {
        BindException target = new BindException("port taken");
        Throwable top = new RuntimeException("outer", new IllegalStateException("mid", target));

        assertSame(target, ThrowableChains.findInChain(top, BindException.class));
        assertNull(ThrowableChains.findInChain(top, FileNotFoundException.class));
    }
}

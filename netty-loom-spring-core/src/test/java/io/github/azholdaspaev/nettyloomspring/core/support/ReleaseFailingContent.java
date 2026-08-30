package io.github.azholdaspaev.nettyloomspring.core.support;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;

import java.nio.charset.StandardCharsets;

/**
 * Fails its own release with something the reference count cannot explain.
 */
public final class ReleaseFailingContent extends DefaultHttpContent {

    public ReleaseFailingContent() {
        super(Unpooled.copiedBuffer("x", StandardCharsets.UTF_8));
    }

    @Override
    public boolean release() {
        throw new IllegalStateException("deallocator failed");
    }
}

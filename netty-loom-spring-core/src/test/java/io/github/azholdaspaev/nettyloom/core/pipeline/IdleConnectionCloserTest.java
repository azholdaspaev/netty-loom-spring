package io.github.azholdaspaev.nettyloom.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdleConnectionCloserTest {

    @Mock
    private ChannelHandlerContext ctx;

    private final IdleConnectionCloser handler = new IdleConnectionCloser();

    @Test
    void shouldCloseChannelWhenIdleStateEventIsTriggered() throws Exception {
        // When
        handler.userEventTriggered(ctx, IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);

        // Then
        verify(ctx).close();
    }

    @Test
    void shouldForwardNonIdleUserEvents() throws Exception {
        // Given
        Object evt = "some-other-event";

        // When
        handler.userEventTriggered(ctx, evt);

        // Then
        verify(ctx).fireUserEventTriggered(evt);
        verify(ctx, never()).close();
    }

    @Test
    void shouldBeSharable() {
        assertThat(IdleConnectionCloser.class).hasAnnotation(ChannelHandler.Sharable.class);
    }
}

package io.github.azholdaspaev.nettyloom.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.azholdaspaev.nettyloom.core.handler.ExceptionHandler;
import io.github.azholdaspaev.nettyloom.core.handler.RequestDispatcher;
import io.github.azholdaspaev.nettyloom.core.handler.RequestHandler;
import io.github.azholdaspaev.nettyloom.core.server.NettyServerConfig;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HttpServerNettyPipelineConfigurerTest {

    @Mock
    private RequestHandler requestHandler;

    @Mock
    private ExceptionHandler exceptionHandler;

    private HttpServerNettyPipelineConfigurer configurer;

    @BeforeEach
    void setUp() {
        var config = NettyServerConfig.builder().build();
        configurer = new HttpServerNettyPipelineConfigurer(config, requestHandler, exceptionHandler);
    }

    @Test
    void shouldReuseDispatcherAcrossChannels() {
        // Given
        var pipeline1 = newMockPipeline();
        var pipeline2 = newMockPipeline();

        // When
        configurer.configure(pipeline1);
        configurer.configure(pipeline2);

        // Then
        var dispatcher1 = captureDispatcher(pipeline1);
        var dispatcher2 = captureDispatcher(pipeline2);
        assertThat(dispatcher1).isSameAs(dispatcher2);
    }

    @Test
    void shouldAddAllHandlersToPipeline() {
        // Given
        var pipeline = newMockPipeline();

        // When
        configurer.configure(pipeline);

        // Then
        verify(pipeline).addLast(eq("httpCodec"), any(HttpServerCodec.class));
        verify(pipeline).addLast(eq("aggregator"), any(HttpObjectAggregator.class));
        verify(pipeline).addLast(eq("idleState"), any(IdleStateHandler.class));
        verify(pipeline).addLast(eq("requestDecoder"), any(HttpRequestDecoder.class));
        verify(pipeline).addLast(eq("responseEncoder"), any(HttpResponseEncoder.class));
        verify(pipeline).addLast(eq("dispatcher"), any(RequestDispatcher.class));
    }

    private static ChannelPipeline newMockPipeline() {
        var pipeline = mock(ChannelPipeline.class);
        when(pipeline.addLast(anyString(), any())).thenReturn(pipeline);
        return pipeline;
    }

    private static RequestDispatcher captureDispatcher(ChannelPipeline pipeline) {
        var captor = ArgumentCaptor.forClass(RequestDispatcher.class);
        verify(pipeline).addLast(eq("dispatcher"), captor.capture());
        return captor.getValue();
    }
}

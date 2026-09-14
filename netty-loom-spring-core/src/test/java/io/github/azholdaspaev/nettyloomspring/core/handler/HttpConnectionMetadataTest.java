package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpConnectionMetadataTest {

    @Test
    void shouldMapFieldsAndDeriveScheme() {
        HttpConnectionMetadata insecure = new HttpConnectionMetadata("[::1]", 54321, "10.0.0.1", 8080, false, "c1");

        assertEquals("[::1]", insecure.remoteAddr());
        assertEquals(54321, insecure.remotePort());
        assertEquals("10.0.0.1", insecure.localAddr());
        assertEquals(8080, insecure.localPort());
        assertEquals(false, insecure.secure());
        assertEquals("c1", insecure.connectionId());
        assertEquals("http", insecure.scheme());

        HttpConnectionMetadata secure = new HttpConnectionMetadata("10.0.0.2", 443, "10.0.0.1", 8443, true, "c2");
        assertEquals(true, secure.secure());
        assertEquals("https", secure.scheme());
    }

    @Test
    void shouldMatchDefaultPortToScheme() {
        assertEquals(80, new HttpConnectionMetadata("", 0, "", 0, false, "").defaultPort());
        assertEquals(443, new HttpConnectionMetadata("", 0, "", 0, true, "").defaultPort());
    }

    @Test
    void shouldYieldDefaultsFromEmbeddedChannel() {
        ChannelHandlerContext ctx =
            new EmbeddedChannel(new ChannelInboundHandlerAdapter() {}).pipeline().firstContext();

        assertEquals(new HttpConnectionMetadata("", 0, "", 0, false, "embedded"), HttpConnectionMetadata.from(ctx));
    }

    @Test
    void shouldTakeLongChannelIdAsConnectionIdFromChannel() {
        ChannelId channelId = DefaultChannelId.newInstance();
        ChannelHandlerContext ctx =
            new EmbeddedChannel(channelId, new ChannelInboundHandlerAdapter() {}).pipeline().firstContext();

        assertEquals(channelId.asLongText(), HttpConnectionMetadata.from(ctx).connectionId(),
            "asLongText is the form ChannelId documents as globally unique; asShortText is not");
    }
}

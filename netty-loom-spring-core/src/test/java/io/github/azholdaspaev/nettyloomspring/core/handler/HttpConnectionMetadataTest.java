package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpConnectionMetadataTest {

    @Test
    void mapsFieldsAndDerivesScheme() {
        HttpConnectionMetadata insecure = new HttpConnectionMetadata("[::1]", 54321, "10.0.0.1", 8080, false);

        assertEquals("[::1]", insecure.remoteAddr());
        assertEquals(54321, insecure.remotePort());
        assertEquals("10.0.0.1", insecure.localAddr());
        assertEquals(8080, insecure.localPort());
        assertEquals(false, insecure.secure());
        assertEquals("http", insecure.scheme());

        HttpConnectionMetadata secure = new HttpConnectionMetadata("10.0.0.2", 443, "10.0.0.1", 8443, true);
        assertEquals(true, secure.secure());
        assertEquals("https", secure.scheme());
    }

    @Test
    void defaultPortMatchesScheme() {
        assertEquals(80, new HttpConnectionMetadata("", 0, "", 0, false).defaultPort());
        assertEquals(443, new HttpConnectionMetadata("", 0, "", 0, true).defaultPort());
    }

    @Test
    void fromEmbeddedChannelYieldsDefaults() {
        ChannelHandlerContext ctx =
            new EmbeddedChannel(new ChannelInboundHandlerAdapter() {}).pipeline().firstContext();

        assertEquals(new HttpConnectionMetadata("", 0, "", 0, false), HttpConnectionMetadata.from(ctx));
    }
}

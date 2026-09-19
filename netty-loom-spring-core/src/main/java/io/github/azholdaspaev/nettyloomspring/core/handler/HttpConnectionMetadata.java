package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.ssl.SslHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public record HttpConnectionMetadata(String remoteAddr,
                                     int remotePort,
                                     String localAddr,
                                     int localPort,
                                     boolean secure,
                                     String connectionId) {

    /**
     * Servlet contract for an unknown remote/local address (getRemoteAddr/getLocalAddr).
     */
    private static final String UNKNOWN_HOST = "";

    /**
     * Servlet contract for an unknown remote/local port (getRemotePort/getLocalPort).
     */
    private static final int UNKNOWN_PORT = 0;

    public static HttpConnectionMetadata from(ChannelHandlerContext ctx) {
        InetSocketAddress remote = toInet(ctx.channel().remoteAddress());
        InetSocketAddress local = toInet(ctx.channel().localAddress());
        boolean secure = ctx.pipeline().get(SslHandler.class) != null;
        // asLongText, not asShortText: ChannelId documents only the long form as globally unique.
        return new HttpConnectionMetadata(
            host(remote),
            port(remote),
            host(local),
            port(local),
            secure,
            ctx.channel().id().asLongText()
        );
    }

    public String scheme() {
        return httpScheme().toString();
    }

    public int defaultPort() {
        return httpScheme().port();
    }

    private HttpScheme httpScheme() {
        return secure ? HttpScheme.HTTPS : HttpScheme.HTTP;
    }

    private static InetSocketAddress toInet(SocketAddress address) {
        return address instanceof InetSocketAddress inet ? inet : null;
    }

    private static String host(InetSocketAddress address) {
        return address == null ? UNKNOWN_HOST : address.getHostString();
    }

    private static int port(InetSocketAddress address) {
        return address == null ? UNKNOWN_PORT : address.getPort();
    }
}

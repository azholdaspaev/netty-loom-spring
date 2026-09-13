package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import jakarta.servlet.ServletConnection;

record NettyServletConnection(HttpConnectionMetadata connection) implements ServletConnection {

    @Override
    public String getConnectionId() {
        return connection.connectionId();
    }

    @Override
    public String getProtocol() {
        // The ALPN identification sequence, which ServletConnection.getProtocol requires for a registered
        // protocol -- so "http/1.1", not HttpVersion.text()'s "HTTP/1.1"; Tomcat's Http11Processor agrees.
        return "http/1.1";
    }

    @Override
    public String getProtocolConnectionId() {
        return "";
    }

    @Override
    public boolean isSecure() {
        return connection.secure();
    }
}

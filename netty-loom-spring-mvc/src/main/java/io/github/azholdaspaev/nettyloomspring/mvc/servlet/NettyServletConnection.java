package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.handler.ssl.ApplicationProtocolNames;
import jakarta.servlet.ServletConnection;

record NettyServletConnection(HttpConnectionMetadata connection) implements ServletConnection {

    @Override
    public String getConnectionId() {
        return connection.connectionId();
    }

    @Override
    public String getProtocol() {
        // ServletConnection.getProtocol requires the ALPN identification sequence for a registered protocol.
        return ApplicationProtocolNames.HTTP_1_1;
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

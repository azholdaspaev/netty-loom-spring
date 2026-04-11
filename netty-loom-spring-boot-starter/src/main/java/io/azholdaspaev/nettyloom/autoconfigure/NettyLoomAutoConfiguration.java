package io.azholdaspaev.nettyloom.autoconfigure;

import io.azholdaspaev.nettyloom.autoconfigure.server.NettyWebServerFactory;
import io.azholdaspaev.nettyloom.core.server.NettyServer;
import io.azholdaspaev.nettyloom.core.server.NettyServerConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class NettyLoomAutoConfiguration {

    @Bean
    public NettyWebServerFactory nettyWebServerFactory(NettyServer nettyServer) {
        return new NettyWebServerFactory(nettyServer);
    }

    @Bean
    public NettyServer nettyServer() {
        return new NettyServer(new NettyServerConfiguration(8080));
    }
}

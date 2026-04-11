package io.azholdaspaev.nettyloom.autoconfigure;

import io.azholdaspaev.nettyloom.autoconfigure.server.NettyWebServerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class NettyLoomAutoConfiguration {

    @Bean
    public NettyWebServerFactory nettyWebServerFactory() {
        return new NettyWebServerFactory();
    }
}

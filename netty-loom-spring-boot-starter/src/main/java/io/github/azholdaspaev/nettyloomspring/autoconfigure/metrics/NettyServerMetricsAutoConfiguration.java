package io.github.azholdaspaev.nettyloomspring.autoconfigure.metrics;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.NettyLoomAutoConfiguration;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.server.NettyServletWebServerFactory;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = NettyLoomAutoConfiguration.class)
@ConditionalOnClass(MeterBinder.class)
@ConditionalOnBean(NettyServletWebServerFactory.class)
public class NettyServerMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    public NettyServerMetrics nettyServerMetrics(HttpConnectionRegistry httpConnectionRegistry) {
        return new NettyServerMetrics(httpConnectionRegistry);
    }
}

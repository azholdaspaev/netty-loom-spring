package io.github.azholdaspaev.nettyloomspring.autoconfigure.properties;

import io.github.azholdaspaev.nettyloomspring.core.server.NettyTransportPreference;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyForwardedHeaderResolver;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyForwardedHeaders;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.time.Duration;
import java.util.List;

/**
 * Validates itself at bind time: Boot applies a {@code @ConfigurationProperties} type that implements
 * {@link Validator} to its own bound instance ({@code ConfigurationPropertiesBinder.getSelfValidator}).
 */
@ConfigurationProperties("server.netty")
public record NettyLoomProperties(
    @DefaultValue("1") int bossThreads,
    @DefaultValue("0") int workerThreads,
    @DefaultValue("true") boolean tcpKeepAlive,
    @DefaultValue("128") int acceptCount,
    @DefaultValue("30s") Duration shutdownGracePeriod,
    @DefaultValue("30s") Duration readTimeout,
    @DefaultValue("60s") Duration writeStallTimeout,
    @DefaultValue("1MB") DataSize maxHttpBodySize,
    @DefaultValue("2MB") DataSize maxSwallowSize,
    @DefaultValue("5s") Duration swallowTimeout,
    @DefaultValue("10000B") DataSize maxHeaderSize,
    @DefaultValue("10000B") DataSize maxInitialLineLength,
    @DefaultValue("10000B") DataSize maxChunkSize,
    @DefaultValue("auto") NettyTransportPreference transport,
    List<String> internalProxies,
    @DefaultValue("x-forwarded") NettyForwardedHeaders forwardedHeaders
) implements Validator {

    public static final List<String> DEFAULT_INTERNAL_PROXIES = List.of("192.168.0.0/16", "172.16.0.0/12",
        "169.254.0.0/16", "fc00::/7", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "fe80::/10", "::1/128");

    public NettyLoomProperties {
        if (internalProxies == null) {
            internalProxies = DEFAULT_INTERNAL_PROXIES;
        }
    }

    @Override
    public boolean supports(Class<?> clazz) {
        return NettyLoomProperties.class.equals(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
        rejectNegative("bossThreads", bossThreads, errors);
        rejectNegative("workerThreads", workerThreads, errors);
        rejectNegative("acceptCount", acceptCount, errors);
        rejectNonPositive("maxHttpBodySize", maxHttpBodySize, errors);
        rejectNonPositive("maxSwallowSize", maxSwallowSize, errors);
        rejectNonPositive("swallowTimeout", swallowTimeout, errors);
        rejectOutsideInt("maxHeaderSize", maxHeaderSize, errors);
        rejectOutsideInt("maxInitialLineLength", maxInitialLineLength, errors);
        rejectOutsideInt("maxChunkSize", maxChunkSize, errors);
        rejectInvalidAddress("internalProxies", internalProxies, errors);
    }

    private void rejectInvalidAddress(String field, List<String> proxies, Errors errors) {
        try {
            new NettyForwardedHeaderResolver(forwardedHeaders, proxies);
        } catch (IllegalArgumentException invalid) {
            errors.rejectValue(field, "address", invalid.getMessage());
        }
    }

    private static void rejectNegative(String field, int value, Errors errors) {
        if (value < 0) {
            errors.rejectValue(field, "negative", "must not be negative");
        }
    }

    private static void rejectNonPositive(String field, DataSize size, Errors errors) {
        if (size.toBytes() <= 0) {
            errors.rejectValue(field, "positive", "must be positive; 0 does not disable the limit");
        }
    }

    private static void rejectNonPositive(String field, Duration duration, Errors errors) {
        if (!duration.isPositive()) {
            errors.rejectValue(field, "positive", "must be positive; 0 does not disable the bound");
        }
    }

    private static void rejectOutsideInt(String field, DataSize size, Errors errors) {
        rejectNonPositive(field, size, errors);
        if (size.toBytes() > Integer.MAX_VALUE) {
            errors.rejectValue(field, "int", "must be at most " + Integer.MAX_VALUE + " bytes");
        }
    }
}

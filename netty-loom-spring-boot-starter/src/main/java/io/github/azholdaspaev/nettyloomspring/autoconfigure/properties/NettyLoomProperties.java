package io.github.azholdaspaev.nettyloomspring.autoconfigure.properties;

import io.github.azholdaspaev.nettyloomspring.core.server.NettyTransportPreference;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.time.Duration;

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
    @DefaultValue("10000B") DataSize maxHeaderSize,
    @DefaultValue("10000B") DataSize maxInitialLineLength,
    @DefaultValue("10000B") DataSize maxChunkSize,
    @DefaultValue("auto") NettyTransportPreference transport
) implements Validator {

    @Override
    public boolean supports(Class<?> clazz) {
        return NettyLoomProperties.class.equals(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
        rejectNonPositive("maxHttpBodySize", maxHttpBodySize, errors);
        rejectOutsideInt("maxHeaderSize", maxHeaderSize, errors);
        rejectOutsideInt("maxInitialLineLength", maxInitialLineLength, errors);
        rejectOutsideInt("maxChunkSize", maxChunkSize, errors);
    }

    private static void rejectNonPositive(String field, DataSize size, Errors errors) {
        if (size.toBytes() <= 0) {
            errors.rejectValue(field, "positive", "must be positive; 0 does not disable the limit");
        }
    }

    private static void rejectOutsideInt(String field, DataSize size, Errors errors) {
        rejectNonPositive(field, size, errors);
        if (size.toBytes() > Integer.MAX_VALUE) {
            errors.rejectValue(field, "int", "must be at most " + Integer.MAX_VALUE + " bytes");
        }
    }
}

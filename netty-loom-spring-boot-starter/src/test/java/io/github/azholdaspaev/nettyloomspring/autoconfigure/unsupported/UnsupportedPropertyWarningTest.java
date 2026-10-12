package io.github.azholdaspaev.nettyloomspring.autoconfigure.unsupported;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.NettyLoomApplications;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnsupportedPropertyWarningTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @ExtendWith(OutputCaptureExtension.class)
    void shouldLogWarnNamingPropertyWhenConfiguredExplicitly(CapturedOutput output) {
        NettyLoomApplications.run("server.http2.enabled=true").close();

        List<String> warnings = output.getAll().lines()
            .filter(line -> line.contains("WARN") && line.contains("Ignoring server.http2.enabled"))
            .toList();
        assertEquals(1, warnings.size(), "expected exactly one startup WARN naming the property, was: " + warnings);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @ExtendWith(OutputCaptureExtension.class)
    void shouldStayQuietWhenPropertiesAreBootDefaults(CapturedOutput output) {
        NettyLoomApplications.run().close();

        List<String> warnings = output.getAll().lines().filter(line -> line.contains("Ignoring server.")).toList();
        assertEquals(List.of(), warnings, "Boot defaults must not log an unsupported-property warning");
    }
}

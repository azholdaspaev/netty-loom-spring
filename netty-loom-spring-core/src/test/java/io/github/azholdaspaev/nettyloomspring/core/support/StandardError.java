package io.github.azholdaspaev.nettyloomspring.core.support;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class StandardError {

    private StandardError() {
    }

    public static String captureDuring(Action action) throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream standardError = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(standardError);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }
}

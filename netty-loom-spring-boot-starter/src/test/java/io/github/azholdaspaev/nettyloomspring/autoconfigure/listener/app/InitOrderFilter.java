package io.github.azholdaspaev.nettyloomspring.autoconfigure.listener.app;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;

public class InitOrderFilter implements Filter {

    private final RecordingListener listener;
    private volatile boolean contextInitializedBeforeInit;

    public InitOrderFilter(RecordingListener listener) {
        this.listener = listener;
    }

    @Override
    public void init(FilterConfig filterConfig) {
        contextInitializedBeforeInit = listener.countOf("contextInitialized") > 0;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        chain.doFilter(request, response);
    }

    public boolean isContextInitializedBeforeInit() {
        return contextInitializedBeforeInit;
    }
}

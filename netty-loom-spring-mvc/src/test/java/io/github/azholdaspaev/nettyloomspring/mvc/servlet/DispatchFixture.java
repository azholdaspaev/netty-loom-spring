package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.io.InputStream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

abstract class DispatchFixture {

    static final HttpConnectionMetadata CONNECTION =
        new HttpConnectionMetadata("198.51.100.2", 1234, "198.51.100.9", 8080, false);

    DefaultNettyServletContext context;
    NettyDispatchFactory factory;
    List<HttpServletRequest> reached;
    List<String> trace;

    @BeforeEach
    void setUpDispatchFixture() {
        context = new DefaultNettyServletContext();
        reached = new ArrayList<>();
        trace = new ArrayList<>();
        terminalIs((request, response) -> {
        });
    }

    final void terminalIs(FilterChain terminal) {
        factory = new NettyDispatchFactory(context, terminal);
        context.setDispatchFactory(factory);
    }

    final void recordTerminal() {
        terminalIs((request, response) -> reached.add((HttpServletRequest) request));
    }

    final void registerFilter(String name, String pattern, EnumSet<DispatcherType> dispatcherTypes) {
        Filter filter = (request, response, chain) -> {
            trace.add(name);
            chain.doFilter(request, response);
        };
        context.addFilter(name, filter).addMappingForUrlPatterns(dispatcherTypes, false, pattern);
    }

    final NettyHttpServletRequest requestFor(String uri, NettyHttpServletResponse response) {
        return requestFor(HttpMethod.GET, uri, response);
    }

    final NettyHttpServletRequest requestFor(HttpMethod method, String uri, NettyHttpServletResponse response) {
        return new NettyHttpServletRequest(
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri), InputStream.nullInputStream(),
            CONNECTION, context, response);
    }
}

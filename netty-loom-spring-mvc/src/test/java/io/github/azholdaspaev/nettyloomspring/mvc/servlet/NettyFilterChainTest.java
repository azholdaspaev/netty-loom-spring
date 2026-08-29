package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.io.InputStream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyFilterChainTest {

    private final List<String> trace = new ArrayList<>();

    private RegisteredFilter recording(String name) {
        Filter filter = (request, response, chain) -> {
            trace.add("enter:" + name);
            chain.doFilter(request, response);
            trace.add("exit:" + name);
        };
        return new RegisteredFilter(name, filter, Set.of("/*"), EnumSet.of(DispatcherType.REQUEST));
    }

    private RegisteredFilter shortCircuiting(String name) {
        Filter filter = (request, response, chain) -> trace.add("short:" + name);
        return new RegisteredFilter(name, filter, Set.of("/*"), EnumSet.of(DispatcherType.REQUEST));
    }

    private FilterChain terminal() {
        return (request, response) -> trace.add("terminal");
    }

    @Test
    void runsFiltersInOrderThenTerminalOnce() throws Exception {
        var chain = new NettyFilterChain(List.of(recording("a"), recording("b")), terminal());

        chain.doFilter(null, null);

        assertEquals(List.of("enter:a", "enter:b", "terminal", "exit:b", "exit:a"), trace);
        assertEquals(1, trace.stream().filter("terminal"::equals).count());
    }

    @Test
    void runsTerminalImmediatelyWhenNoFilters() throws Exception {
        var chain = new NettyFilterChain(List.of(), terminal());

        chain.doFilter(null, null);

        assertEquals(List.of("terminal"), trace);
    }

    @Test
    void shortCircuitsWhenFilterDoesNotProceed() throws Exception {
        var chain = new NettyFilterChain(List.of(shortCircuiting("a"), recording("b")), terminal());

        chain.doFilter(null, null);

        assertEquals(List.of("short:a"), trace);
        assertTrue(trace.stream().noneMatch("terminal"::equals));
    }

    @Test
    void propagatesExceptionFromFilter() {
        Filter boom = (request, response, chain) -> {
            throw new ServletException("boom");
        };
        var chain = new NettyFilterChain(
            List.of(new RegisteredFilter("boom", boom, Set.of("/*"), EnumSet.of(DispatcherType.REQUEST))),
            terminal());

        assertThrows(ServletException.class, () -> chain.doFilter(null, null));
        assertTrue(trace.stream().noneMatch("terminal"::equals));
    }

    @Test
    void propagatesRequestAndResponseWrappersToDownstreamFilterAndTerminal() throws Exception {
        var original = new NettyHttpServletRequest(
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x"), InputStream.nullInputStream(),
            new HttpConnectionMetadata("", 0, "", 0, false),
            new DefaultNettyServletContext(),
            new NettyHttpServletResponse());
        var wrappedRequest = new HttpServletRequestWrapper(original);
        var wrappedResponse = new HttpServletResponseWrapper(new NettyHttpServletResponse());

        Filter wrapping = (request, response, chain) -> chain.doFilter(wrappedRequest, wrappedResponse);
        var downstreamRequest = new ServletRequest[1];
        var downstreamResponse = new ServletResponse[1];
        Filter downstream = (request, response, chain) -> {
            downstreamRequest[0] = request;
            downstreamResponse[0] = response;
            chain.doFilter(request, response);
        };
        var terminalRequest = new ServletRequest[1];
        FilterChain terminal = (request, response) -> terminalRequest[0] = request;

        var chain = new NettyFilterChain(
            List.of(
                new RegisteredFilter("wrap", wrapping, Set.of("/*"), EnumSet.of(DispatcherType.REQUEST)),
                new RegisteredFilter("down", downstream, Set.of("/*"), EnumSet.of(DispatcherType.REQUEST))),
            terminal);

        chain.doFilter(original, new NettyHttpServletResponse());

        assertSame(wrappedRequest, downstreamRequest[0]);
        assertSame(wrappedResponse, downstreamResponse[0]);
        assertSame(wrappedRequest, terminalRequest[0]);
    }

    @Test
    void terminalCanThrowCheckedException() {
        FilterChain throwing = (request, response) -> {
            throw new IOException("io");
        };
        var chain = new NettyFilterChain(List.of(), throwing);

        assertThrows(IOException.class, () -> chain.doFilter(null, null));
    }
}

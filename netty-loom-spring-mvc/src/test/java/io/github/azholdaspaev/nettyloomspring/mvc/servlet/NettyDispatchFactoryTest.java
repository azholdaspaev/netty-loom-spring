package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.DispatcherType;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyDispatchFactoryTest extends DispatchFixture {

    private void dispatch(String uri) throws Exception {
        recordTerminal();
        var response = new NettyHttpServletResponse();
        var request = requestFor(uri, response);

        factory.chainFor(request).doFilter(request, response);
    }

    @Test
    void shouldChainMatchingFiltersInRegistrationOrder() throws Exception {
        registerFilter("first", "/*", EnumSet.of(DispatcherType.REQUEST));
        registerFilter("elsewhere", "/other/*", EnumSet.of(DispatcherType.REQUEST));
        registerFilter("onForward", "/*", EnumSet.of(DispatcherType.FORWARD));
        registerFilter("last", "/t", EnumSet.of(DispatcherType.REQUEST));

        dispatch("/t");

        assertEquals(List.of("first", "last"), trace,
            "only the filters whose pattern and dispatcher type match run, in registration order");
        assertEquals(1, reached.size(), "the chain ends in the terminal exactly once; got " + reached);
    }

    @Test
    void shouldRunOnlyTerminalWhenNoFilterMatches() throws Exception {
        registerFilter("elsewhere", "/other/*", EnumSet.of(DispatcherType.REQUEST));

        dispatch("/t");

        assertEquals(List.of(), trace);
        assertEquals(1, reached.size(), "an empty chain still reaches the terminal; got " + reached);
    }
}

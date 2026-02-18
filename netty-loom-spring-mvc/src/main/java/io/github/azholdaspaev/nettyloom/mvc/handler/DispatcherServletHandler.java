package io.github.azholdaspaev.nettyloom.mvc.handler;

import io.github.azholdaspaev.nettyloom.core.handler.RequestHandler;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpRequest;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;
import io.github.azholdaspaev.nettyloom.mvc.servlet.NettyHttpServletRequest;
import io.github.azholdaspaev.nettyloom.mvc.servlet.NettyHttpServletResponse;
import jakarta.servlet.ServletContext;
import org.springframework.web.servlet.DispatcherServlet;

public class DispatcherServletHandler implements RequestHandler {

    private final DispatcherServlet dispatcherServlet;
    private final ServletContext servletContext;

    public DispatcherServletHandler(DispatcherServlet dispatcherServlet, ServletContext servletContext) {
        this.dispatcherServlet = dispatcherServlet;
        this.servletContext = servletContext;
    }

    @Override
    public NettyHttpResponse handle(NettyHttpRequest request) {
        NettyHttpServletRequest httpRequest = new NettyHttpServletRequest(request, servletContext);
        NettyHttpServletResponse httpResponse = new NettyHttpServletResponse();

        try {
            dispatcherServlet.service(httpRequest, httpResponse);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return httpResponse.asNettyHttpResponse();
    }
}

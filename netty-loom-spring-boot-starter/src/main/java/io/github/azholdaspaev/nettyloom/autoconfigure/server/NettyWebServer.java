package io.github.azholdaspaev.nettyloom.autoconfigure.server;

import io.github.azholdaspaev.nettyloom.core.http.DefaultNettyHttpResponse;
import io.github.azholdaspaev.nettyloom.core.pipeline.HttpServerNettyPipelineConfigurer;
import io.github.azholdaspaev.nettyloom.core.server.NettyServer;
import io.github.azholdaspaev.nettyloom.core.server.NettyServerConfig;
import io.github.azholdaspaev.nettyloom.core.server.NettyServerInitializer;
import io.github.azholdaspaev.nettyloom.mvc.handler.DispatcherServletHandler;
import io.github.azholdaspaev.nettyloom.mvc.servlet.NettyServletContext;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.util.Collections;
import java.util.Enumeration;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

public class NettyWebServer implements WebServer {

    private final int port;
    private final NettyServletContext servletContext;
    private NettyServer nettyServer;

    public NettyWebServer(int port, ServletContextInitializer[] initializers) {
        this.port = port;
        this.servletContext = new NettyServletContext();

        try {
            for (ServletContextInitializer initializer : initializers) {
                initializer.onStartup(servletContext);
            }
        } catch (Exception e) {
            throw new WebServerException("Failed to initialize servlet context", e);
        }
    }

    @Override
    public void start() throws WebServerException {
        try {
            WebApplicationContext wac = (WebApplicationContext)
                    servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);

            DispatcherServlet dispatcherServlet = wac.getBean(DispatcherServlet.class);
            dispatcherServlet.init(new SimpleServletConfig(servletContext));

            DispatcherServletHandler handler = new DispatcherServletHandler(dispatcherServlet, servletContext);

            NettyServerConfig config = NettyServerConfig.builder().port(port).build();

            HttpServerNettyPipelineConfigurer pipelineConfigurer = new HttpServerNettyPipelineConfigurer(config);

            NettyServerInitializer serverInitializer = new NettyServerInitializer(
                    handler,
                    (ex, req) ->
                            DefaultNettyHttpResponse.builder().statusCode(500).build(),
                    pipelineConfigurer);

            nettyServer = new NettyServer(config, serverInitializer);
            nettyServer.start();
        } catch (Exception e) {
            throw new WebServerException("Failed to start Netty server", e);
        }
    }

    @Override
    public void stop() throws WebServerException {
        if (nettyServer != null) {
            nettyServer.stop();
        }
    }

    @Override
    public int getPort() {
        return nettyServer != null ? nettyServer.getPort() : port;
    }

    private record SimpleServletConfig(ServletContext servletContext) implements ServletConfig {

        @Override
        public String getServletName() {
            return "dispatcherServlet";
        }

        @Override
        public ServletContext getServletContext() {
            return servletContext;
        }

        @Override
        public String getInitParameter(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.emptyEnumeration();
        }
    }
}

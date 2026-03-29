package io.github.azholdaspaev.nettyloom.autoconfigure.server;

import io.github.azholdaspaev.nettyloom.core.http.DefaultNettyHttpResponse;
import io.github.azholdaspaev.nettyloom.core.pipeline.HttpServerNettyPipelineConfigurer;
import io.github.azholdaspaev.nettyloom.core.server.NettyServer;
import io.github.azholdaspaev.nettyloom.core.server.NettyServerConfig;
import io.github.azholdaspaev.nettyloom.core.server.NettyServerInitializer;
import io.github.azholdaspaev.nettyloom.core.server.NettyServerState;
import io.github.azholdaspaev.nettyloom.mvc.handler.DispatcherServletHandler;
import io.github.azholdaspaev.nettyloom.mvc.servlet.DefaultNettyServletContext;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

public class NettyWebServer implements WebServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebServer.class);

    private final int port;
    private final DefaultNettyServletContext servletContext;
    private NettyServer nettyServer;
    private final ExecutorService executorService;

    public NettyWebServer(int port, ServletContextInitializer[] initializers) {
        this.port = port;
        this.servletContext = new DefaultNettyServletContext();
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();

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

            HttpServerNettyPipelineConfigurer pipelineConfigurer =
                    new HttpServerNettyPipelineConfigurer(config, handler, (_, _) -> DefaultNettyHttpResponse.builder()
                            .statusCode(500)
                            .build(), executorService);

            NettyServerInitializer serverInitializer = new NettyServerInitializer(pipelineConfigurer);

            nettyServer = new NettyServer(config, serverInitializer);
            nettyServer.start();
        } catch (Exception e) {
            throw new WebServerException("Failed to start Netty server", e);
        }
    }

    @Override
    public void stop() throws WebServerException {
        if (nettyServer != null && nettyServer.getState() == NettyServerState.RUNNING) {
            nettyServer.stop();
        }
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                executorService.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while waiting for executor service to shut down");
            }
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

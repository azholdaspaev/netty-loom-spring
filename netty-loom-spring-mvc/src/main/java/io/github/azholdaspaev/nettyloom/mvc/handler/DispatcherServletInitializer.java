package io.github.azholdaspaev.nettyloom.mvc.handler;

import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

public class DispatcherServletInitializer {

    public DispatcherServlet initialize(WebApplicationContext applicationContext) {
        return new DispatcherServlet(applicationContext);
    }
}

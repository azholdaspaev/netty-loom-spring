/**
 * The Jakarta Servlet API over Netty -- request, response, session, filter chain and request
 * dispatch -- implemented as far as a Spring MVC application uses it. {@link NettyServletContext}
 * is the container's {@code ServletContext} with every inherited method stubbed to throw, plus what
 * the container itself sets on it; {@link DefaultNettyServletContext} implements the part Spring MVC
 * reaches. {@link NettyErrorPageResolver} and {@link NettyCookieSameSiteResolver} are the two
 * policies the starter supplies from Boot's registrations.
 */
package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

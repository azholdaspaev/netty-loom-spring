/**
 * Boot's embedded-server SPI: {@link NettyServletWebServerFactory} is the
 * {@code ServletWebServerFactory} and {@link NettyServletWebServer} the {@code WebServer} it
 * produces, wrapping the core server. The remaining types carry Boot's registered error pages,
 * cookie {@code SameSite} suppliers and the servlet context's stop-phase lifecycle into the servlet
 * bridge.
 */
package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

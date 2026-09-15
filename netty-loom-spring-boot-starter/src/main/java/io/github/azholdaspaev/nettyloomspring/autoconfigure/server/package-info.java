/**
 * Boot's embedded-server SPI: {@link NettyWebServerFactory} is the {@code ServletWebServerFactory}
 * and {@link NettyWebServer} the {@code WebServer} it produces, wrapping the core server. The
 * remaining types carry Boot's registered error pages, cookie {@code SameSite} suppliers and session
 * store lifecycle into the servlet bridge.
 */
package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

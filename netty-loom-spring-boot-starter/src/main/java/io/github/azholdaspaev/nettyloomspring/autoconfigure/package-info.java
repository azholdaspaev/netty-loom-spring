/**
 * The Spring Boot entry point: {@link NettyLoomAutoConfiguration} assembles the core server, the
 * default pipeline definition and the MVC dispatcher into the {@code ServletWebServerFactory} Boot
 * starts. No bean here is {@code @ConditionalOnMissingBean}, so an application replacing one declares
 * its own as {@code @Primary}.
 */
package io.github.azholdaspaev.nettyloomspring.autoconfigure;

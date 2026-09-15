/**
 * The Spring Boot entry point: {@link NettyLoomAutoConfiguration} assembles the core server, the
 * default pipeline definition and the MVC dispatcher into the {@code ServletWebServerFactory} Boot
 * starts. No bean here is {@code @ConditionalOnMissingBean}, so an application replacing the
 * {@code HttpRequestDispatcher} or the {@code NettyPipelineDefinition} declares its own as
 * {@code @Primary}; replacing the {@code ServletWebServerFactory} needs this auto-configuration
 * excluded, since Boot refuses to start with two.
 */
package io.github.azholdaspaev.nettyloomspring.autoconfigure;

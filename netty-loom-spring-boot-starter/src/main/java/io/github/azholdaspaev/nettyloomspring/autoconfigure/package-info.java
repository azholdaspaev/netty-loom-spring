/**
 * The Spring Boot entry point: {@link NettyLoomAutoConfiguration} assembles the core server, the
 * default pipeline definition and the MVC dispatcher into the {@code ServletWebServerFactory} Boot
 * starts. Every bean here is {@code @ConditionalOnMissingBean}, so an application replacing the
 * {@code HttpRequestDispatcher}, the {@code NettyPipelineDefinition} or the
 * {@code ServletWebServerFactory} declares its own and the auto-configured one backs off; the
 * dispatch executor alone is guarded by name, {@code nettyLoomDispatchExecutor}. Every guard searches
 * the current context only, so a child context that starts its own server registers its own bean
 * graph; injection still sees the whole hierarchy, so a parent bean marked {@code @Primary} wins.
 */
package io.github.azholdaspaev.nettyloomspring.autoconfigure;

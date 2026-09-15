/**
 * The server lifecycle, with no Spring dependency: {@link NettyServer} binds, serves and shuts down
 * over the settings in {@link NettyServerConfiguration}, on the transport
 * {@link NettyIoHandlerFactory} resolves from a {@link NettyTransportPreference}, and
 * {@link NettyServerChannelInitializer} applies the pipeline definition to each accepted channel.
 */
package io.github.azholdaspaev.nettyloomspring.core.server;

/**
 * How each accepted connection's channel pipeline is built: a {@link NettyPipelineDefinition} is the
 * ordered list of {@link NettyPipelineStep}s that
 * {@link io.github.azholdaspaev.nettyloomspring.core.server.NettyServerChannelInitializer} applies to
 * every new channel. The starter assembles the default list, so a definition an application declares
 * replaces the whole pipeline, not one step of it.
 */
package io.github.azholdaspaev.nettyloomspring.core.pipeline;

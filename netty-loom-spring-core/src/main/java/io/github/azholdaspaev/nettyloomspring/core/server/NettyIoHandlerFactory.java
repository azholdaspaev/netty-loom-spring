package io.github.azholdaspaev.nettyloomspring.core.server;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a {@link NettyTransportPreference} to a concrete {@link NettyTransport} once at
 * construction, probing {@link Epoll}/{@link KQueue} availability, and exposes the matching
 * {@link IoHandlerFactory} and server channel class for {@link NettyServer}.
 */
public class NettyIoHandlerFactory {

    private static final Logger log = LoggerFactory.getLogger(NettyIoHandlerFactory.class);

    private final NettyTransport transport;

    public NettyIoHandlerFactory(NettyTransportPreference transport) {
        this.transport = NettyTransport.resolve(transport, Epoll.isAvailable(), KQueue.isAvailable());
        log.info("Netty transport selected: {}", this.transport);
    }

    public IoHandlerFactory getIoHandlerFactory() {
        return transport.newIoHandlerFactory();
    }

    public Class<? extends ServerChannel> getServerChannelClass() {
        return transport.serverChannelClass();
    }
}

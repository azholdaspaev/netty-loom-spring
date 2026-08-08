package io.github.azholdaspaev.nettyloomspring.core.server;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.Locale;

/**
 * The resolved Netty I/O transports. Each constant pairs the {@link IoHandlerFactory} for the event
 * loop group with the matching {@link ServerChannel} type for the server bootstrap — the two must
 * come from the same family or {@code bind()} fails.
 */
enum NettyTransport {
    NIO {
        @Override
        IoHandlerFactory newIoHandlerFactory() {
            return NioIoHandler.newFactory();
        }

        @Override
        Class<? extends ServerChannel> serverChannelClass() {
            return NioServerSocketChannel.class;
        }
    },
    EPOLL {
        @Override
        IoHandlerFactory newIoHandlerFactory() {
            return EpollIoHandler.newFactory();
        }

        @Override
        Class<? extends ServerChannel> serverChannelClass() {
            return EpollServerSocketChannel.class;
        }
    },
    KQUEUE {
        @Override
        IoHandlerFactory newIoHandlerFactory() {
            return KQueueIoHandler.newFactory();
        }

        @Override
        Class<? extends ServerChannel> serverChannelClass() {
            return KQueueServerSocketChannel.class;
        }
    };

    /**
     * A fresh factory on every call, deliberately: boss and worker each need their own.
     */
    abstract IoHandlerFactory newIoHandlerFactory();

    abstract Class<? extends ServerChannel> serverChannelClass();

    /**
     * Resolves the configured transport preference to a concrete transport. An explicitly requested
     * native transport fails fast when unavailable rather than silently degrading to NIO.
     *
     * @throws IllegalArgumentException if {@code requested} is null or not one of the known values
     */
    static NettyTransport resolve(String requested, boolean epollAvailable, boolean kqueueAvailable) {
        if (requested == null) {
            throw new IllegalArgumentException("transport must be one of: auto, nio, epoll, kqueue");
        }
        return switch (requested.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> epollAvailable ? EPOLL : kqueueAvailable ? KQUEUE : NIO;
            case "nio" -> NIO;
            case "epoll" -> requireAvailable(EPOLL, epollAvailable);
            case "kqueue" -> requireAvailable(KQUEUE, kqueueAvailable);
            default -> throw new IllegalArgumentException(
                "Unknown transport '" + requested + "'. Valid values: auto, nio, epoll, kqueue");
        };
    }

    private static NettyTransport requireAvailable(NettyTransport transport, boolean available) {
        if (!available) {
            throw new IllegalStateException(
                transport.name().toLowerCase(Locale.ROOT)
                    + " transport requested but not available on this platform");
        }
        return transport;
    }
}

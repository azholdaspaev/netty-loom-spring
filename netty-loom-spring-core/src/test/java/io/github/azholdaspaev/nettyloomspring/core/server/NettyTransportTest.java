package io.github.azholdaspaev.nettyloomspring.core.server;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyTransportTest {

    // --- Pure resolution logic (no mocks, no OS dependency) ---

    @Test
    void autoPrefersEpollWhenAvailable() {
        assertEquals(NettyTransport.EPOLL, NettyTransport.resolve(NettyTransportPreference.AUTO, true, true));
        assertEquals(NettyTransport.EPOLL, NettyTransport.resolve(NettyTransportPreference.AUTO, true, false));
    }

    @Test
    void autoFallsBackToKqueueWhenOnlyKqueueAvailable() {
        assertEquals(NettyTransport.KQUEUE, NettyTransport.resolve(NettyTransportPreference.AUTO, false, true));
    }

    @Test
    void autoFallsBackToNioWhenNoNativeAvailable() {
        assertEquals(NettyTransport.NIO, NettyTransport.resolve(NettyTransportPreference.AUTO, false, false));
    }

    @Test
    void nioIsAlwaysSelectedRegardlessOfAvailability() {
        assertEquals(NettyTransport.NIO, NettyTransport.resolve(NettyTransportPreference.NIO, true, true));
        assertEquals(NettyTransport.NIO, NettyTransport.resolve(NettyTransportPreference.NIO, false, false));
    }

    @Test
    void explicitEpollSelectedWhenAvailable() {
        assertEquals(NettyTransport.EPOLL, NettyTransport.resolve(NettyTransportPreference.EPOLL, true, false));
    }

    @Test
    void explicitKqueueSelectedWhenAvailable() {
        assertEquals(NettyTransport.KQUEUE, NettyTransport.resolve(NettyTransportPreference.KQUEUE, false, true));
    }

    @Test
    void explicitEpollFailsFastWhenUnavailable() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> NettyTransport.resolve(NettyTransportPreference.EPOLL, false, false));
        assertTrue(ex.getMessage().contains("epoll"));
    }

    @Test
    void explicitKqueueFailsFastWhenUnavailable() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> NettyTransport.resolve(NettyTransportPreference.KQUEUE, false, false));
        assertTrue(ex.getMessage().contains("kqueue"));
    }

    // --- Live native paths: prove the platform's classifier jar is actually on the classpath ---

    @Test
    @EnabledOnOs(OS.LINUX)
    void selectsEpollOnLinux() {
        assertTrue(Epoll.isAvailable(), "epoll native library must be on the classpath on Linux");
        NettyIoHandlerFactory factory = new NettyIoHandlerFactory(NettyTransportPreference.AUTO);
        assertEquals(EpollServerSocketChannel.class, factory.getServerChannelClass());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void selectsKqueueOnMac() {
        assertTrue(KQueue.isAvailable(), "kqueue native library must be on the classpath on macOS");
        NettyIoHandlerFactory factory = new NettyIoHandlerFactory(NettyTransportPreference.AUTO);
        assertEquals(KQueueServerSocketChannel.class, factory.getServerChannelClass());
    }

    @Test
    void nioFactorySelectsNioServerChannel() {
        NettyIoHandlerFactory factory = new NettyIoHandlerFactory(NettyTransportPreference.NIO);
        assertEquals(NioServerSocketChannel.class, factory.getServerChannelClass());
    }
}

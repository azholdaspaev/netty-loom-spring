package io.github.azholdaspaev.nettyloom.core.executor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Factory for creating virtual thread executors.
 * Uses Java 21+ virtual threads for efficient blocking operation handling.
 */
public final class VirtualThreadExecutorFactory {

    private VirtualThreadExecutorFactory() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Creates a new virtual thread per task executor.
     * Each submitted task runs on a new virtual thread.
     *
     * @return ExecutorService backed by virtual threads
     */
    public static ExecutorService create() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Creates a virtual thread per task executor with a custom thread name prefix.
     *
     * @param namePrefix prefix for virtual thread names
     * @return ExecutorService backed by virtual threads
     */
    public static ExecutorService create(String namePrefix) {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(namePrefix, 0).factory()
        );
    }
}

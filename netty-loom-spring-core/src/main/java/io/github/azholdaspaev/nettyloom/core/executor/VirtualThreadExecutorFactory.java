package io.github.azholdaspaev.nettyloom.core.executor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Factory that creates virtual thread executors.
 * Uses Java 21+ virtual threads for efficient blocking operation handling.
 */
public class VirtualThreadExecutorFactory implements ExecutorFactory {

    private final String namePrefix;

    /**
     * Creates a factory that produces virtual thread executors with default thread names.
     */
    public VirtualThreadExecutorFactory() {
        this.namePrefix = null;
    }

    /**
     * Creates a factory that produces virtual thread executors with a custom thread name prefix.
     *
     * @param namePrefix prefix for virtual thread names
     */
    public VirtualThreadExecutorFactory(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    /**
     * Creates a new virtual thread per task executor.
     * Each submitted task runs on a new virtual thread.
     *
     * @return ExecutorService backed by virtual threads
     */
    @Override
    public ExecutorService create() {
        if (namePrefix == null) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(namePrefix, 0).factory()
        );
    }
}

package io.github.azholdaspaev.nettyloom.core.executor;

import java.util.concurrent.ExecutorService;

/**
 * Factory interface for creating ExecutorService instances.
 * Allows customization of how request-handling threads are created.
 */
@FunctionalInterface
public interface ExecutorFactory {
    /**
     * Creates an ExecutorService for handling requests.
     *
     * @return the executor service
     */
    ExecutorService create();
}

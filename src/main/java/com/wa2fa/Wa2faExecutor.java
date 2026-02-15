package com.wa2fa;

import org.jboss.logging.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared bounded thread pool for all wa2fa async operations.
 *
 * Replaces:
 * - {@code new Thread(() -> ...).start()} in login notifications
 * - {@code CompletableFuture.runAsync(() -> ...)} that uses the common ForkJoinPool
 *
 * Uses a fixed-size pool (4 threads) with named threads for easy debugging.
 * All threads are daemon threads so they don't prevent JVM shutdown.
 */
public class Wa2faExecutor {

    private static final Logger log = Logger.getLogger(Wa2faExecutor.class);

    private static final int POOL_SIZE = 4;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(POOL_SIZE,
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "wa2fa-async-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });

    private Wa2faExecutor() {}

    /**
     * Submit an async task to the wa2fa thread pool.
     * Use this instead of {@code new Thread()} or {@code CompletableFuture.runAsync()}.
     *
     * @param task the task to execute asynchronously
     */
    public static void submit(Runnable task) {
        EXECUTOR.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.errorf("Unhandled exception in wa2fa async task: %s", e.getMessage());
            }
        });
    }

    /**
     * Get the underlying executor for use with CompletableFuture.
     * Usage: {@code CompletableFuture.runAsync(() -> ..., Wa2faExecutor.executor())}
     */
    public static ExecutorService executor() {
        return EXECUTOR;
    }
}

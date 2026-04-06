package com.ethnicthv.ecs.core.execution;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fixed worker pool for ECS parallel query execution.
 * The caller also participates in draining work ranges.
 */
public final class EcsWorkerPool implements AutoCloseable {
    private static final String WORKER_COUNT_PROPERTY = "ecs.workerCount";
    private static final int BATCHES_PER_PARTICIPANT = 4;

    @FunctionalInterface
    public interface IntRangeTask {
        void run(int startInclusive, int endExclusive);
    }

    private final Object stateLock = new Object();
    private final Thread[] workers;
    private final AtomicInteger nextIndex = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile boolean closed;
    private volatile int generation;
    private volatile CountDownLatch completion;
    private volatile IntRangeTask task;
    private volatile int itemCount;
    private volatile int batchSize;

    public EcsWorkerPool() {
        this(resolveWorkerThreadCount());
    }

    public EcsWorkerPool(int workerThreadCount) {
        if (workerThreadCount < 0) {
            throw new IllegalArgumentException("workerThreadCount must be >= 0");
        }
        this.workers = new Thread[workerThreadCount];
        for (int i = 0; i < workerThreadCount; i++) {
            Thread worker = Thread.ofPlatform()
                .name("ecs-worker-" + (i + 1))
                .daemon(true)
                .unstarted(this::runWorker);
            workers[i] = worker;
            worker.start();
        }
    }

    public int workerThreadCount() {
        return workers.length;
    }

    public int parallelism() {
        return workers.length + 1;
    }

    public int recommendBatchSize(int totalItems) {
        if (totalItems <= 1) {
            return 1;
        }
        int participants = Math.max(1, parallelism());
        int targetBatches = Math.max(1, participants * BATCHES_PER_PARTICIPANT);
        return Math.max(1, (totalItems + targetBatches - 1) / targetBatches);
    }

    public void parallelFor(int totalItems, IntRangeTask rangeTask) {
        parallelFor(totalItems, recommendBatchSize(totalItems), rangeTask);
    }

    public void parallelFor(int totalItems, int requestedBatchSize, IntRangeTask rangeTask) {
        Objects.requireNonNull(rangeTask, "rangeTask");
        if (totalItems <= 0) {
            return;
        }

        int resolvedBatchSize = Math.max(1, Math.min(requestedBatchSize, totalItems));
        if (workers.length == 0 || totalItems <= resolvedBatchSize) {
            rangeTask.run(0, totalItems);
            return;
        }

        int batchCount = (totalItems + resolvedBatchSize - 1) / resolvedBatchSize;
        if (batchCount <= 1) {
            rangeTask.run(0, totalItems);
            return;
        }

        CountDownLatch localCompletion;
        synchronized (stateLock) {
            ensureOpen();
            task = rangeTask;
            itemCount = totalItems;
            batchSize = resolvedBatchSize;
            nextIndex.set(0);
            failure.set(null);
            localCompletion = new CountDownLatch(workers.length);
            completion = localCompletion;
            generation++;
            stateLock.notifyAll();
        }

        drainRanges(rangeTask, totalItems, resolvedBatchSize);
        awaitCompletion(localCompletion);

        Throwable thrown = failure.get();
        if (thrown != null) {
            if (thrown instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Parallel ECS task failed", thrown);
        }
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            stateLock.notifyAll();
        }

        for (Thread worker : workers) {
            boolean interrupted = false;
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runWorker() {
        int observedGeneration = 0;
        while (true) {
            CountDownLatch localCompletion;
            IntRangeTask localTask;
            int localItemCount;
            int localBatchSize;

            synchronized (stateLock) {
                while (!closed && observedGeneration == generation) {
                    try {
                        stateLock.wait();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                observedGeneration = generation;
                localCompletion = completion;
                localTask = task;
                localItemCount = itemCount;
                localBatchSize = batchSize;
            }

            try {
                drainRanges(localTask, localItemCount, localBatchSize);
            } finally {
                localCompletion.countDown();
            }
        }
    }

    private void drainRanges(IntRangeTask rangeTask, int totalItems, int resolvedBatchSize) {
        while (failure.get() == null) {
            int start = nextIndex.getAndAdd(resolvedBatchSize);
            if (start >= totalItems) {
                return;
            }
            int end = Math.min(start + resolvedBatchSize, totalItems);
            try {
                rangeTask.run(start, end);
            } catch (Throwable thrown) {
                if (failure.compareAndSet(null, thrown)) {
                    nextIndex.set(totalItems);
                }
                return;
            }
        }
    }

    private void awaitCompletion(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("EcsWorkerPool is closed");
        }
    }

    private static int resolveWorkerThreadCount() {
        int fallback = Math.max(0, Runtime.getRuntime().availableProcessors() - 1);
        String override = System.getProperty(WORKER_COUNT_PROPERTY);
        if (override == null || override.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(override.trim()));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}

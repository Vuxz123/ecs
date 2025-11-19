package com.ethnicthv.ecs.core.system;

import com.ethnicthv.ecs.core.system.annotation.Query;

/**
 * Defines the execution strategy for a query in a system.
 * <p>
 * This enum is used in conjunction with the {@link Query} annotation to specify
 * whether entity processing should be done sequentially or in parallel.
 *
 * Guidance:
 * - Choose the mode based on the workload characteristics and thread-safety requirements,
 *   not on an arbitrary entity-count threshold.
 *
 * @see Query
 */
public enum ExecutionMode {
    /**
     * Process entities sequentially in a single thread.
     * <p>
     * Recommended when:
     * <ul>
     *   <li>Per-entity work is simple/lightweight (e.g., a few arithmetic ops)</li>
     *   <li>Processing involves I/O or blocking operations</li>
     *   <li>Specific ordering is required</li>
     *   <li>Thread safety is a concern or shared state is hard to synchronize</li>
     * </ul>
     */
    SEQUENTIAL,

    /**
     * Process entities in parallel across multiple CPU cores.
     * <p>
     * Recommended when:
     * <ul>
     *   <li>Per-entity work is CPU-intensive (e.g., heavy math, physics, AI)</li>
     *   <li>The consumer is thread-safe and shared state is minimized or properly synchronized</li>
     *   <li>Processing order does not matter</li>
     * </ul>
     * <p>
     * Note: Parallelism has overhead (task scheduling, synchronization). Benchmark your
     * specific workload to validate that PARALLEL provides a real speedup on your target
     * hardware and configuration.
     */
    PARALLEL
}

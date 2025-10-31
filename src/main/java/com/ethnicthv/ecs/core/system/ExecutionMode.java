package com.ethnicthv.ecs.core.system;

/**
 * Defines the execution strategy for a query in a system.
 * <p>
 * This enum is used in conjunction with the {@link Query} annotation to specify
 * whether entity processing should be done sequentially or in parallel.
 *
 * @see Query
 */
public enum ExecutionMode {
    /**
     * Process entities sequentially in a single thread.
     * <p>
     * This is the default and safest mode. Use this when:
     * <ul>
     *   <li>Entity count is small (< 1000)</li>
     *   <li>Processing logic requires specific ordering</li>
     *   <li>Processing involves I/O operations</li>
     *   <li>Thread safety is a concern</li>
     * </ul>
     */
    SEQUENTIAL,

    /**
     * Process entities in parallel across multiple CPU cores.
     * <p>
     * Use this mode when:
     * <ul>
     *   <li>Entity count is large (> 1000)</li>
     *   <li>Processing is CPU-intensive</li>
     *   <li>Processing logic is thread-safe</li>
     *   <li>Order doesn't matter</li>
     * </ul>
     * <p>
     * <strong>WARNING:</strong> When using PARALLEL mode, ensure that all operations
     * in your system's update logic are thread-safe. Use concurrent collections
     * and atomic primitives where necessary.
     */
    PARALLEL
}


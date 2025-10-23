package com.ethnicthv.ecs.core;

/**
 * Base interface for component storage systems.
 * Each component type has its own storage with contiguous memory layout.
 */
public interface ComponentStorage extends AutoCloseable {
    /**
     * Get the maximum capacity of this storage
     */
    int capacity();

    /**
     * Get the current number of active components
     */
    int size();

    /**
     * Clear all components
     */
    void clear();
}

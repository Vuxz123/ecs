package com.ethnicthv.ecs.core.components;

import java.util.Arrays;

/**
 * Sparse Set for fast entity-to-component mapping.
 * - O(1) insertion, deletion, lookup
 * - Maintains dense packing for cache-friendly iteration
 * - Used to map entity IDs to dense component indices
 */
public class SparseSet {
    private final int[] sparse;  // entity -> dense index
    private final int[] dense;   // dense index -> entity
    private int size;

    public SparseSet(int maxEntities) {
        this.sparse = new int[maxEntities];
        this.dense = new int[maxEntities];
        this.size = 0;
        Arrays.fill(sparse, -1);
    }

    /**
     * Add an entity to the set, returning its dense index
     */
    public int add(int entityId) {
        if (has(entityId)) {
            return sparse[entityId];
        }

        int denseIndex = size;
        sparse[entityId] = denseIndex;
        dense[denseIndex] = entityId;
        size++;
        return denseIndex;
    }

    /**
     * Remove an entity from the set using swap-and-pop
     */
    public void remove(int entityId) {
        if (!has(entityId)) {
            return;
        }

        int denseIndex = sparse[entityId];
        int lastEntity = dense[size - 1];

        // Swap with last element
        dense[denseIndex] = lastEntity;
        sparse[lastEntity] = denseIndex;

        // Mark as removed
        sparse[entityId] = -1;
        size--;
    }

    /**
     * Check if entity exists in the set
     */
    public boolean has(int entityId) {
        return entityId >= 0 && entityId < sparse.length && sparse[entityId] != -1;
    }

    /**
     * Get the dense index for an entity
     */
    public int getDenseIndex(int entityId) {
        return sparse[entityId];
    }

    /**
     * Get the entity ID at a dense index
     */
    public int getEntity(int denseIndex) {
        return dense[denseIndex];
    }

    /**
     * Get the number of entities in the set
     */
    public int size() {
        return size;
    }

    /**
     * Clear all entities
     */
    public void clear() {
        Arrays.fill(sparse, -1);
        size = 0;
    }
}

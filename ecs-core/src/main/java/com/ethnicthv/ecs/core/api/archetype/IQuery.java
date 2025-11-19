package com.ethnicthv.ecs.core.api.archetype;

import com.ethnicthv.ecs.core.components.ComponentHandle;

/**
 * Immutable, thread-safe query interface for executing operations on matched entities.
 * <p>
 * This interface represents a pre-configured query that cannot be modified after creation.
 * It provides methods for iterating over entities, chunks, and archetypes that
 * match the query criteria.
 * <p>
 * Instances of this interface are typically created via {@link IQueryBuilder#build()}.
 */
public interface IQuery {

    /**
     * Functional interface for consuming entities.
     */
    @FunctionalInterface
    interface EntityConsumer {
        /**
         * Process a single entity.
         *
         * @param entityId the entity ID
         * @param componentHandles array of MemorySegment handles for matched components
         * @param archetype the archetype containing this entity
         */
        void accept(int entityId, ComponentHandle[] componentHandles, IArchetype archetype);
    }

    /**
     * Functional interface for consuming archetypes.
     */
    @FunctionalInterface
    interface ArchetypeConsumer {
        /**
         * Process an entire archetype.
         *
         * @param archetype the archetype
         */
        void accept(IArchetype archetype);
    }

    /**
     * Functional interface for consuming chunks.
     */
    @FunctionalInterface
    interface ChunkConsumer {
        /**
         * Process a single chunk.
         *
         * @param chunk the archetype chunk
         * @param archetype the archetype containing this chunk
         */
        void accept(IArchetypeChunk chunk, IArchetype archetype);
    }

    /**
     * New minimal API entrypoint
     */
    default void runQuery() {
        throw new UnsupportedOperationException("runQuery is not implemented by this query instance");
    }

    /**
     * Iterate over all matching entities sequentially.
     * <p>
     * The consumer is called once per entity with access to the entity ID,
     * component data handles, and the containing archetype.
     *
     * @param consumer the entity consumer
     */
    default void forEachEntity(EntityConsumer consumer) {
        throw new UnsupportedOperationException("Deprecated: use runQuery()");
    }

    /**
     * Iterate over all matching entities in parallel.
     * <p>
     * The consumer MUST be thread-safe. Entities will be processed concurrently
     * across multiple threads for better performance on large datasets.
     * <p>
     * <strong>Warning:</strong> The consumer must be thread-safe and must not
     * modify shared state without proper synchronization.
     *
     * @param consumer the thread-safe entity consumer
     */
    default void forEachParallel(EntityConsumer consumer) {
        throw new UnsupportedOperationException("Deprecated: use runQuery()");
    }

    /**
     * Iterate over all matching archetypes.
     * <p>
     * This is useful for batch operations on entire archetypes rather than
     * individual entities.
     *
     * @param consumer the archetype consumer
     */
    default void forEach(ArchetypeConsumer consumer) {
        throw new UnsupportedOperationException("Deprecated");
    }

    /**
     * Iterate over all matching chunks.
     * <p>
     * This provides access to the raw chunk data for advanced use cases.
     *
     * @param consumer the chunk consumer
     */
    default void forEachChunk(ChunkConsumer consumer) {
        throw new UnsupportedOperationException("Deprecated");
    }

    /**
     * Count the total number of entities matching this query.
     * <p>
     * This is more efficient than iterating and counting manually.
     *
     * @return the number of matching entities
     */
    default int count() {
        throw new UnsupportedOperationException("Deprecated");
    }
}

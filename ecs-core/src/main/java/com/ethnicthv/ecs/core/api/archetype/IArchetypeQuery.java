package com.ethnicthv.ecs.core.api.archetype;

import com.ethnicthv.ecs.core.components.ComponentHandle;

/**
 * Public API for querying entities in the ECS world based on component requirements.
 * <p>
 * Provides a fluent interface for building queries with:
 * - Required components (with)
 * - Excluded components (without)
 * - Optional components (any)
 */
public interface IArchetypeQuery {

    /**
     * Require entities to have this component.
     *
     * @param componentClass The component class that must be present.
     * @return This query for method chaining.
     */
    <T> IArchetypeQuery with(Class<T> componentClass);

    /**
     * Require entities to NOT have this component.
     *
     * @param componentClass The component class that must NOT be present.
     * @return This query for method chaining.
     */
    <T> IArchetypeQuery without(Class<T> componentClass);

    /**
     * Require entities to have at least one of the specified components.
     *
     * @param componentClasses The component classes, at least one of which must be present.
     * @return This query for method chaining.
     */
    IArchetypeQuery any(Class<?>... componentClasses);

    /**
     * Execute the query and iterate over matching archetypes.
     *
     * @param consumer The callback that receives each matching archetype.
     */
    void forEach(ArchetypeConsumer consumer);

    /**
     * Execute the query and iterate over matching chunks.
     *
     * @param consumer The callback that receives each matching chunk.
     */
    void forEachChunk(ChunkConsumer consumer);

    /**
     * Execute the query and iterate over matching entities.
     *
     * @param consumer The callback that receives each matching entity.
     */
    void forEachEntity(EntityConsumer consumer);

    /**
     * Count the total number of entities matching this query.
     *
     * @return The number of matching entities.
     */
    int count();

    void forEachParallel(EntityConsumer consumer);

    /**
     * Callback interface for archetype iteration.
     */
    @FunctionalInterface
    interface ArchetypeConsumer {
        void accept(IArchetype archetype);
    }

    /**
     * Callback interface for chunk iteration.
     */
    @FunctionalInterface
    interface ChunkConsumer {
        void accept(IArchetypeChunk chunk, IArchetype archetype);
    }

    /**
     * Callback interface for entity iteration.
     */
    @FunctionalInterface
    interface EntityConsumer {
        void accept(int entityId, ComponentHandle[] handles, IArchetype archetype);
    }
}

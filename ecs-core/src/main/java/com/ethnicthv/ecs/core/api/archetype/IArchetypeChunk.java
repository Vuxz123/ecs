package com.ethnicthv.ecs.core.api.archetype;

import java.lang.foreign.MemorySegment;

/**
 * Provides a read-only view of an ArchetypeChunk, which is a contiguous block of memory
 * storing entities and their component data.
 * <p>
 * This interface allows safe, read-only access to the data within a chunk without exposing
 * the underlying memory management and modification mechanisms.
 */
public interface IArchetypeChunk {
    int size();

    /**
     * Returns the number of entities currently stored in this chunk.
     *
     * @return The number of active entities.
     */
    int getEntityCount();

    /**
     * Returns the maximum number of entities this chunk can hold.
     *
     * @return The total capacity of the chunk.
     */
    int getCapacity();

    /**
     * Retrieves the ID of the entity at a specific index within the chunk.
     *
     * @param indexInChunk The zero-based index of the entity within this chunk.
     * @return The unique ID of the entity.
     */
    int getEntityId(int indexInChunk);

    /**
     * Retrieves the memory segment representing the data for a specific component of an entity.
     *
     * @param componentIndex The internal index of the component type within the archetype.
     *                       This should be obtained from {@link IArchetype#indexOfComponentType(int)}.
     * @param indexInChunk   The zero-based index of the entity within this chunk.
     * @return A {@link MemorySegment} containing the component's data.
     */
    MemorySegment getComponentData(int componentIndex, int indexInChunk);
}



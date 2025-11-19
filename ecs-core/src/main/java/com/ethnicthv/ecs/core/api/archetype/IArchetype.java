package com.ethnicthv.ecs.core.api.archetype;

import com.ethnicthv.ecs.core.archetype.ArchetypeChunk;
import com.ethnicthv.ecs.core.archetype.ComponentMask;

import java.util.List;

/**
 * Provides a read-only view of an Archetype, which represents a unique combination of component types.
 * <p>
 * This interface exposes information about the structure of the archetype, such as its component types
 * and the collection of chunks that store the actual entity and component data.
 */
public interface IArchetype {
    /**
     * Returns an array of component type IDs that define this archetype.
     * The order of IDs is consistent and is used for indexing component data within chunks.
     *
     * @return An array of unique component type IDs.
     */
    int[] getComponentTypeIds();

    /**
     * Retrieves a snapshot list of all chunks belonging to this archetype.
     * The returned list provides a read-only view of the chunks at a specific moment in time.
     *
     * @return A list of {@link IArchetypeChunk} instances.
     */
    List<? extends IArchetypeChunk> getChunks();

    /**
     * Returns the total number of chunks currently managed by this archetype.
     *
     * @return The number of chunks.
     */
    int getChunkCount();

    /**
     * Returns the total number of entities currently stored across all chunks in this archetype.
     *
     * @return The total entity count.
     */
    int getEntityCount();

    /**
     * Finds the internal index for a given component type ID within this archetype's structure.
     * This index is used to access the correct component data array within a chunk.
     *
     * @param componentTypeId The unique ID of the component type.
     * @return The zero-based index of the component type, or -1 if the component is not part of this archetype.
     */
    int indexOfComponentType(int componentTypeId);

    void forEach(ArchetypeIterator o);

    ComponentMask getMask();

    @FunctionalInterface
    interface ArchetypeIterator {
        void accept(int entityId, ArchetypeChunk.ChunkLocation location, ArchetypeChunk chunk);
    }
}
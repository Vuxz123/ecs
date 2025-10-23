package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.ComponentDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Archetype groups entities that share the same set of components.
 * It stores component descriptors and manages a list of chunks.
 */
public final class Archetype {

    private final int[] componentIds;
    private final ComponentDescriptor[] descriptors;
    private final long[] componentElementSizes;
    private final int entitiesPerChunk;
    private final List<ArchetypeChunk> chunks = new ArrayList<>();

    // Choose a chunk byte budget (tunable)
    private static final int CHUNK_SIZE = 16 * 1024;
    private static final int DEFAULT_ENTITIES_PER_CHUNK = 64; // when descriptors report 0 size

    public Archetype(ComponentMask mask, int[] componentIds, ComponentDescriptor[] descriptors, Arena arena) {
        // Note: mask parameter kept for backward compatibility, but not stored
        if (componentIds.length != descriptors.length) {
            throw new IllegalArgumentException("componentIds/descriptors length mismatch");
        }
        this.componentIds = componentIds;
        this.descriptors = descriptors;
        this.componentElementSizes = new long[descriptors.length];

        long totalPerEntity = 0;
        for (int i = 0; i < descriptors.length; i++) {
            long s = descriptors[i].getTotalSize();
            componentElementSizes[i] = s;
            totalPerEntity += s;
        }

        if (totalPerEntity <= 0) {
            // if no components or sizes are zero, use a sensible default count
            this.entitiesPerChunk = DEFAULT_ENTITIES_PER_CHUNK;
        } else {
            this.entitiesPerChunk = Math.max(1, (int) (CHUNK_SIZE / totalPerEntity));
        }

        // Create initial chunk
        this.chunks.add(new ArchetypeChunk(descriptors, componentElementSizes, entitiesPerChunk, arena));
    }

    /**
     * Get component mask - computed on demand from componentIds
     */
    public ComponentMask getMask() {
        ComponentMask mask = new ComponentMask();
        for (int componentId : componentIds) {
            mask = mask.set(componentId);
        }
        return mask;
    }

    public int[] getComponentIds() {
        return componentIds;
    }

    public ComponentDescriptor[] getDescriptors() {
        return descriptors;
    }

    public int getEntitiesPerChunk() {
        return entitiesPerChunk;
    }

    public List<ArchetypeChunk> getChunks() {
        return chunks;
    }

    public synchronized ArchetypeChunk.ChunkLocation addEntity(int entityId) {
        for (int i = 0; i < chunks.size(); i++) {
            ArchetypeChunk chunk = chunks.get(i);
            int idx = chunk.allocateSlot(entityId);
            if (idx >= 0) {
                return new ArchetypeChunk.ChunkLocation(i, idx);
            }
        }
        // No free slot -> allocate new chunk using same arena as first
        Arena arena = chunks.get(0).getArena();
        ArchetypeChunk newChunk = new ArchetypeChunk(descriptors, componentElementSizes, entitiesPerChunk, arena);
        chunks.add(newChunk);
        int idx = newChunk.allocateSlot(entityId);
        return new ArchetypeChunk.ChunkLocation(chunks.size() - 1, idx);
    }

    public synchronized void removeEntity(ArchetypeChunk.ChunkLocation location) {
        if (location.chunkIndex >= chunks.size()) return;
        ArchetypeChunk chunk = chunks.get(location.chunkIndex);
        chunk.freeSlot(location.indexInChunk);
        // Optionally free empty chunks (except keep one)
        if (chunk.isEmpty() && chunks.size() > 1) {
            chunks.remove(location.chunkIndex);
        }
    }

    public ArchetypeChunk getChunk(int chunkIndex) {
        return chunks.get(chunkIndex);
    }

    public int chunkCount() {
        return chunks.size();
    }

    public int getEntityCount() {
        int total = 0;
        for (ArchetypeChunk c : chunks) total += c.size();
        return total;
    }

    /**
     * Iterate over all entities in this archetype
     */
    public void forEach(ArchetypeIterator iterator) {
        for (int chunkIdx = 0; chunkIdx < chunks.size(); chunkIdx++) {
            ArchetypeChunk chunk = chunks.get(chunkIdx);
            for (int i = 0; i < chunk.size(); i++) {
                iterator.accept(chunk.getEntityId(i), new ArchetypeChunk.ChunkLocation(chunkIdx, i), chunk);
            }
        }
    }

    /**
     * Get component data for an entity
     */
    public MemorySegment getComponentData(ArchetypeChunk.ChunkLocation location, int componentIndex) {
        ArchetypeChunk chunk = chunks.get(location.chunkIndex);
        return chunk.getComponentData(componentIndex, location.indexInChunk);
    }

    /**
     * Set component data for an entity
     */
    public void setComponentData(ArchetypeChunk.ChunkLocation location, int componentIndex, MemorySegment data) {
        ArchetypeChunk chunk = chunks.get(location.chunkIndex);
        chunk.setComponentData(componentIndex, location.indexInChunk, data);
    }

    @FunctionalInterface
    public interface ArchetypeIterator {
        void accept(int entityId, ArchetypeChunk.ChunkLocation location, ArchetypeChunk chunk);
    }
}

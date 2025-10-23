package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.ComponentDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * Single chunk storing SoA arrays for each component.
 */
public final class ArchetypeChunk {

    private final ComponentDescriptor[] descriptors;
    private final long[] elementSizes;
    private final MemorySegment[] componentArrays;
    private final int capacity;
    private final boolean[] occupied;
    private final int[] entityIds;
    private final Arena arena;
    private int size; // number of occupied slots

    public static final class ChunkLocation {
        public final int chunkIndex;
        public final int indexInChunk;

        public ChunkLocation(int chunkIndex, int indexInChunk) {
            this.chunkIndex = chunkIndex;
            this.indexInChunk = indexInChunk;
        }
    }

    public ArchetypeChunk(ComponentDescriptor[] descriptors, long[] elementSizes, int capacity, Arena arena) {
        this.descriptors = descriptors;
        this.elementSizes = elementSizes;
        this.capacity = capacity;
        this.arena = arena;
        this.occupied = new boolean[capacity];
        this.entityIds = new int[capacity];
        this.componentArrays = new MemorySegment[descriptors.length];
        this.size = 0;

        // Allocate per-component arrays
        for (int i = 0; i < descriptors.length; i++) {
            long bytes = elementSizes[i] * (long) capacity;
            if (bytes <= 0) {
                throw new IllegalArgumentException("Invalid element size for component " + i + ": " + elementSizes[i]);
            }
            this.componentArrays[i] = arena.allocate(bytes);
        }

        Arrays.fill(entityIds, -1);
    }

    public Arena getArena() {
        return arena;
    }

    /**
     * Allocate a free slot and associate with entityId.
     * Returns index or -1 if full.
     */
    public synchronized int allocateSlot(int entityId) {
        if (size >= capacity) return -1;
        for (int i = 0; i < capacity; i++) {
            if (!occupied[i]) {
                occupied[i] = true;
                entityIds[i] = entityId;
                size++;
                // Optionally zero memory for each component element
                return i;
            }
        }
        return -1;
    }

    public synchronized void freeSlot(int index) {
        if (index < 0 || index >= capacity) return;
        if (occupied[index]) {
            occupied[index] = false;
            entityIds[index] = -1;
            size--;
        }
        // content left as-is; caller may overwrite
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int getEntityId(int index) {
        return entityIds[index];
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Current number of occupied slots in this chunk
     */
    public int size() {
        return size;
    }

    /**
     * Return a MemorySegment slice pointing to the component element for an entity slot.
     * The returned slice is a view into the backing component array (zero-copy).
     */
    public MemorySegment getComponentData(int componentIndex, int elementIndex) {
        if (componentIndex < 0 || componentIndex >= componentArrays.length) {
            throw new IndexOutOfBoundsException("componentIndex out of range");
        }
        if (elementIndex < 0 || elementIndex >= capacity) {
            throw new IndexOutOfBoundsException("elementIndex out of range");
        }
        long offset = elementSizes[componentIndex] * (long) elementIndex;
        return componentArrays[componentIndex].asSlice(offset, elementSizes[componentIndex]);
    }

    /**
     * Copy provided segment into the element slot for componentIndex.
     * Expects src.byteSize() <= elementSize for that component.
     */
    public void setComponentData(int componentIndex, int elementIndex, MemorySegment src) {
        MemorySegment dst = getComponentData(componentIndex, elementIndex);
        long copyLen = Math.min(dst.byteSize(), src.byteSize());
        MemorySegment.copy(src, 0, dst, 0, copyLen);
    }
}

package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.ComponentDescriptor;
import com.ethnicthv.ecs.core.ECSMemoryAllocationException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Single chunk storing SoA arrays for each component.
 */
public final class ArchetypeChunk {

    private final ComponentDescriptor[] descriptors;
    private final long[] elementSizes;
    private final MemorySegment[] componentArrays;
    private final int capacity;
    // Lock-free free list: Treiber stack of free indices
    private final AtomicInteger freeHead; // head index of free list, -1 if none
    private final int[] nextFree; // next pointer for each slot
    private final AtomicIntegerArray entityIds; // -1 means free
    private final Arena arena;
    private final AtomicInteger size; // number of occupied slots
    // Queue ticket to suppress duplicate enqueues into availableChunks
    private final AtomicInteger queued = new AtomicInteger(0); // 0 = not queued, 1 = queued
    private static final int BITS_PER_WORD = 64;
    private final AtomicLongArray occupancy; // bit i = 1 if slot i is occupied

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
        this.componentArrays = new MemorySegment[descriptors.length];
        this.size = new AtomicInteger(0);
        this.entityIds = new AtomicIntegerArray(capacity);
        this.nextFree = new int[capacity];
        this.occupancy = new AtomicLongArray((capacity + BITS_PER_WORD - 1) / BITS_PER_WORD);
        // Initialize free list: 0 -> 1 -> 2 -> ... -> (capacity-1) -> -1
        for (int i = 0; i < capacity - 1; i++) {
            nextFree[i] = i + 1;
            entityIds.set(i, -1);
        }
        if (capacity > 0) {
            nextFree[capacity - 1] = -1;
            entityIds.set(capacity - 1, -1);
        }
        this.freeHead = new AtomicInteger(capacity > 0 ? 0 : -1);

        // Allocate per-component arrays and zero-initialize them
        for (int i = 0; i < descriptors.length; i++) {
            long bytes = elementSizes[i] * (long) capacity;
            if (bytes <= 0) {
                throw new IllegalArgumentException("Invalid element size for component " + i + ": " + elementSizes[i]);
            }
            try {
                this.componentArrays[i] = arena.allocate(bytes);
                // zero entire component array to ensure new slots start clean
                this.componentArrays[i].fill((byte) 0);
            } catch (OutOfMemoryError oom) {
                throw new ECSMemoryAllocationException("Failed to allocate component array (index=" + i + ", bytes=" + bytes + ", capacity=" + capacity + ")", oom);
            }
        }

        // keep entityIds initialized to -1
        // Arrays.fill handled above via AtomicIntegerArray sets
    }

    public Arena getArena() {
        return arena;
    }

    /**
     * Allocate a free slot and associate with entityId.
     * Returns index or -1 if full.
     */
    public int allocateSlot(int entityId) {
        while (true) {
            int head = freeHead.get();
            if (head == -1) return -1; // full
            int next = nextFree[head];
            if (freeHead.compareAndSet(head, next)) {
                // zero out the slot data before making it visible to iterators or readers
                zeroSlot(head);
                // publish entity id for the claimed slot
                entityIds.set(head, entityId);
                // mark occupancy bit so iterators can observe
                setBit(head);
                size.incrementAndGet();
                return head;
            }
            // CAS failed: retry
        }
    }

    public void freeSlot(int index) {
        if (index < 0 || index >= capacity) return;
        // mark entity id as free first (helps readers)
        if (entityIds.getAndSet(index, -1) == -1) {
            // already free; ignore double free
            return;
        }
        // clear occupancy bit (iteration won't visit this index after this point)
        clearBit(index);
        // push index onto free list
        while (true) {
            int head = freeHead.get();
            nextFree[index] = head;
            if (freeHead.compareAndSet(head, index)) {
                size.decrementAndGet();
                return;
            }
            // retry on contention
        }
    }

    private void zeroSlot(int idx) {
        for (int c = 0; c < componentArrays.length; c++) {
            long elemSize = elementSizes[c];
            long offset = elemSize * (long) idx;
            componentArrays[c].asSlice(offset, elemSize).fill((byte) 0);
        }
    }

    public boolean isEmpty() {
        return size.get() == 0;
    }

    public int getEntityId(int index) {
        return entityIds.get(index);
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Current number of occupied slots in this chunk
     */
    public int size() {
        return size.get();
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

    public boolean hasFree() {
        return freeHead.get() != -1;
    }

    /**
     * Try to mark this chunk as queued; returns true if caller should enqueue its index.
     */
    public boolean tryMarkQueued() {
        return queued.compareAndSet(0, 1);
    }

    /**
     * Clear queued mark when a consumer polls this chunk from the queue.
     */
    public void markDequeued() {
        queued.set(0);
    }

    private void setBit(int idx) {
        int word = idx >>> 6; // /64
        long mask = 1L << (idx & 63);
        while (true) {
            long cur = occupancy.get(word);
            long nxt = cur | mask;
            if (cur == nxt || occupancy.compareAndSet(word, cur, nxt)) return;
        }
    }

    private void clearBit(int idx) {
        int word = idx >>> 6;
        long mask = 1L << (idx & 63);
        while (true) {
            long cur = occupancy.get(word);
            long nxt = cur & ~mask;
            if (cur == nxt || occupancy.compareAndSet(word, cur, nxt)) return;
        }
    }

    /**
     * Find next occupied slot index >= fromIndex using occupancy bitset. Returns -1 if none.
     */
    public int nextOccupiedIndex(int fromIndex) {
        if (fromIndex < 0) fromIndex = 0;
        if (fromIndex >= capacity) return -1;
        int word = fromIndex >>> 6;
        int bit = fromIndex & 63;
        int words = occupancy.length();
        long w = (word < words) ? occupancy.get(word) : 0L;
        // mask off bits before 'bit'
        w &= (-1L << bit);
        while (true) {
            if (w != 0) {
                int offset = Long.numberOfTrailingZeros(w);
                int idx = (word << 6) + offset;
                if (idx < capacity) return idx;
                return -1;
            }
            word++;
            if (word >= words) return -1;
            w = occupancy.get(word);
        }
    }
}

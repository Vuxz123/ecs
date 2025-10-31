package com.ethnicthv.ecs.core.archetype;

import com.ethnicthv.ecs.core.api.archetype.IArchetype;
import com.ethnicthv.ecs.core.api.archetype.IArchetypeChunk;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Archetype groups entities that share the same set of components.
 * It stores component descriptors and manages a list of chunks.
 */
public final class Archetype implements IArchetype {

    private final int[] componentIds;
    private final ComponentDescriptor[] descriptors;
    private final long[] componentElementSizes;
    private final int entitiesPerChunk;
    private final ComponentMask mask; // cached mask
    private final Arena arena; // arena for new chunk allocations

    // Cache-friendly chunk storage
    private volatile ArchetypeChunk[] chunks;
    private final AtomicInteger chunkCount = new AtomicInteger(0); // also acts as publish barrier
    private final ReentrantLock resizeLock = new ReentrantLock();

    // Queue of chunk indices that currently have at least one free slot
    private final ConcurrentLinkedQueue<Integer> availableChunks = new ConcurrentLinkedQueue<>();
    // Approximate count of available chunks in the queue (kept exact via ticket discipline)
    private final AtomicInteger availableCount = new AtomicInteger(0);

    // Lock-free provisioning control: at most one provisioner at a time
    private final AtomicBoolean provisioning = new AtomicBoolean(false);
    private static final int PROVISION_THRESHOLD = 2; // when below, proactively create one more chunk
    // Spin attempts to bridge provisioning without blocking. Tuned to balance latency and CPU burn.
    private static final int SPIN_WAIT_ITERATIONS = 32;

    // Choose a chunk byte budget (tunable)
    private static final int CHUNK_SIZE = 16 * 1024;
    private static final int DEFAULT_ENTITIES_PER_CHUNK = 64; // when descriptors report 0 size

    private final ConcurrentHashMap<Integer, Integer> componentIndexMap = new ConcurrentHashMap<>();

    public Archetype(ComponentMask mask, int[] componentIds, ComponentDescriptor[] descriptors, Arena arena) {
        if (componentIds.length != descriptors.length) {
            throw new IllegalArgumentException("componentIds/descriptors length mismatch");
        }
        this.componentIds = componentIds;
        this.descriptors = descriptors;
        this.componentElementSizes = new long[descriptors.length];
        this.mask = mask; // store provided mask
        this.arena = arena;

        long totalPerEntity = 0;
        for (int i = 0; i < descriptors.length; i++) {
            long s = descriptors[i].getTotalSize();
            componentElementSizes[i] = s;
            totalPerEntity += s;
        }

        if (totalPerEntity <= 0) {
            this.entitiesPerChunk = DEFAULT_ENTITIES_PER_CHUNK;
        } else {
            this.entitiesPerChunk = Math.max(1, (int) (CHUNK_SIZE / totalPerEntity));
        }

        // init chunk array
        this.chunks = new ArchetypeChunk[Math.max(4, 1)];
        // Remove precomputed typeIndex; use computeIfAbsent in indexOfComponentType instead
        // Create initial chunk at index 0
        ArchetypeChunk first = new ArchetypeChunk(descriptors, componentElementSizes, entitiesPerChunk, arena);
        this.chunks[0] = first;
        chunkCount.set(1);
        // initial chunk has all slots free; enqueue its index (0) with ticket
        if (first.tryMarkQueued()) {
            this.availableChunks.add(0);
            this.availableCount.incrementAndGet();
        }
    }

    /**
     * Get component mask (cached)
     */
    public ComponentMask getMask() { return mask; }

    public int[] getComponentIds() { return componentIds; }

    @Override
    public int[] getComponentTypeIds() {
        return getComponentIds();
    }

    public ComponentDescriptor[] getDescriptors() { return descriptors; }

    public int getEntitiesPerChunk() { return entitiesPerChunk; }

    /**
     * Return a snapshot list of chunks. Order is physical array order.
     */
    @Override
    public List<IArchetypeChunk> getChunks() {
        ArchetypeChunk[] snap = this.chunks;
        int count = this.chunkCount.get();
        List<IArchetypeChunk> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(snap[i]);
        }
        return list;
    }

    /**
     * Get a direct reference to the current chunks array.
     * <p>
     * This method provides thread-safe access to the chunks array for parallel iteration.
     * Since the 'chunks' field is declared as volatile, reading it guarantees visibility
     * of the most recent array reference. The array itself may be replaced during resize
     * operations, but the reference returned here is stable for the duration of its use.
     * <p>
     * This is primarily intended for parallel processing where multiple threads need to
     * iterate over chunks concurrently without creating defensive copies.
     *
     * @return A reference to the current chunks array. The caller should also read
     *         {@link #chunkCount()} to determine how many valid entries exist.
     */
    public ArchetypeChunk[] getChunksSnapshot() {
        return this.chunks;
    }

    public ArchetypeChunk.ChunkLocation addEntity(int entityId) {
        // Structured, non-recursive allocation loop
        while (true) {
            // 1) Try fast-path: consume available chunk(s)
            ArchetypeChunk.ChunkLocation loc = tryFastPathAllocate(entityId);
            if (loc != null) return loc;

            // 2) If queue empty, attempt to become the provisioner (lock-free CAS)
            if (provisioning.compareAndSet(false, true)) {
                try {
                    // Double-check fast-path in case capacity was added while acquiring provisioning
                    loc = tryFastPathAllocate(entityId);
                    if (loc != null) return loc;
                    // Create exactly one new chunk and allocate from it
                    return createChunkAndAllocate(entityId);
                } finally {
                    provisioning.set(false);
                }
            }

            // 3) Someone else is provisioning. Spin briefly and retry.
            for (int i = 0; i < SPIN_WAIT_ITERATIONS; i++) {
                loc = tryFastPathAllocate(entityId);
                if (loc != null) return loc;
                Thread.onSpinWait();
            }
            // Loop and try again
        }
    }

    // Fast-path allocation from availableChunks; returns null if none succeed
    private ArchetypeChunk.ChunkLocation tryFastPathAllocate(int entityId) {
        Integer idxChunk;
        while ((idxChunk = availableChunks.poll()) != null) {
            availableCount.decrementAndGet();
            ArchetypeChunk[] snap = this.chunks;
            int count = this.chunkCount.get();
            if (idxChunk < 0 || idxChunk >= count) continue;
            ArchetypeChunk chunk = snap[idxChunk];
            if (chunk == null) continue;
            chunk.markDequeued();
            int slot = chunk.allocateSlot(entityId);
            if (slot >= 0) {
                // Opportunistic replenishment when running low
                maybeProvision();
                if (chunk.hasFree() && chunk.tryMarkQueued()) {
                    availableChunks.offer(idxChunk);
                    availableCount.incrementAndGet();
                }
                return new ArchetypeChunk.ChunkLocation(idxChunk, slot);
            }
            // If full now, do not requeue; proceed to next
        }
        return null;
    }

    // Create one chunk, append into array, allocate a slot in it, and enqueue if it still has free capacity
    private ArchetypeChunk.ChunkLocation createChunkAndAllocate(int entityId) {
        ArchetypeChunk newChunk = new ArchetypeChunk(descriptors, componentElementSizes, entitiesPerChunk, arena);
        int newIndex = appendChunk(newChunk);
        int slot = newChunk.allocateSlot(entityId);
        if (newChunk.hasFree() && newChunk.tryMarkQueued()) {
            availableChunks.offer(newIndex);
            availableCount.incrementAndGet();
        }
        return new ArchetypeChunk.ChunkLocation(newIndex, slot);
    }

    private int appendChunk(ArchetypeChunk newChunk) {
        resizeLock.lock();
        try {
            ArchetypeChunk[] arr = this.chunks;
            int idx = chunkCount.get();
            if (idx < arr.length) {
                arr[idx] = newChunk;
                // publish via chunkCount volatile increment
                chunkCount.incrementAndGet();
                return idx;
            }
            // resize: double capacity
            int newCap = Math.max(4, arr.length << 1);
            ArchetypeChunk[] newArr = new ArchetypeChunk[newCap];
            System.arraycopy(arr, 0, newArr, 0, idx);
            newArr[idx] = newChunk;
            // publish new array first
            this.chunks = newArr;
            // then publish count
            chunkCount.incrementAndGet();
            return idx;
        } finally {
            resizeLock.unlock();
        }
    }

    private void maybeProvision() {
        if (availableCount.get() < PROVISION_THRESHOLD && provisioning.compareAndSet(false, true)) {
            try {
                ArchetypeChunk extra = new ArchetypeChunk(descriptors, componentElementSizes, entitiesPerChunk, arena);
                int id = appendChunk(extra);
                if (extra.tryMarkQueued()) {
                    availableChunks.offer(id);
                    availableCount.incrementAndGet();
                }
            } finally {
                provisioning.set(false);
            }
        }
    }

    public void removeEntity(ArchetypeChunk.ChunkLocation location) {
        int idx = location.chunkIndex;
        ArchetypeChunk[] snap = this.chunks;
        if (idx < 0 || idx >= chunkCount.get()) return;
        ArchetypeChunk chunk = snap[idx];
        if (chunk == null) return;
        chunk.freeSlot(location.indexInChunk);
        if (chunk.hasFree() && chunk.tryMarkQueued()) {
            availableChunks.offer(idx);
            availableCount.incrementAndGet();
        }
    }

    public ArchetypeChunk getChunk(int chunkIndex) {
        ArchetypeChunk[] snap = this.chunks;
        int count = this.chunkCount.get();
        if (chunkIndex < 0 || chunkIndex >= count) throw new IndexOutOfBoundsException();
        return snap[chunkIndex];
    }

    public int chunkCount() { return this.chunkCount.get(); }

    @Override
    public int getChunkCount() {
        return chunkCount();
    }

    @Override
    public int getEntityCount() {
        ArchetypeChunk[] snap = this.chunks;
        int count = this.chunkCount.get();
        int total = 0;
        for (int i = 0; i < count; i++) total += snap[i].size();
        return total;
    }

    /**
     * Iterate over all entities in this archetype.
     * Weakly consistent: concurrent adds/removes may or may not be observed by this traversal,
     * and an entity may be skipped or visited once depending on timing. The traversal never throws
     * due to concurrent modification and aims to be cache-friendly.
     */
    public void forEach(ArchetypeIterator iterator) {
        ArchetypeChunk[] snap = this.chunks;
        int count = this.chunkCount.get();
        for (int chunkId = 0; chunkId < count; chunkId++) {
            ArchetypeChunk chunk = snap[chunkId];
            int i = chunk.nextOccupiedIndex(0);
            while (i != -1) {
                int eid = chunk.getEntityId(i);
                if (eid != -1) iterator.accept(eid, new ArchetypeChunk.ChunkLocation(chunkId, i), chunk);
                i = chunk.nextOccupiedIndex(i + 1);
            }
        }
    }

    /**
     * Get component data for an entity
     */
    public MemorySegment getComponentData(ArchetypeChunk.ChunkLocation location, int componentIndex) {
        ArchetypeChunk[] snap = this.chunks;
        return snap[location.chunkIndex].getComponentData(componentIndex, location.indexInChunk);
    }

    /**
     * Set component data for an entity
     */
    public void setComponentData(ArchetypeChunk.ChunkLocation location, int componentIndex, MemorySegment data) {
        ArchetypeChunk[] snap = this.chunks;
        snap[location.chunkIndex].setComponentData(componentIndex, location.indexInChunk, data);
    }

    /**
     * Get the index of a component type ID within this archetype's component arrays, or -1 if absent.
     * Uses a thread-safe lazy cache to compute the mapping at most once per component type id.
     */
    public int indexOfComponentType(int componentTypeId) {
        return componentIndexMap.computeIfAbsent(componentTypeId, tid -> {
            for (int i = 0; i < componentIds.length; i++) {
                if (componentIds[i] == tid) {
                    return i;
                }
            }
            return -1; // not present in this archetype
        });
    }
}

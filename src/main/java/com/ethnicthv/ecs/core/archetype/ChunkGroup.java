package com.ethnicthv.ecs.core.archetype;

import com.ethnicthv.ecs.core.api.archetype.IArchetype;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ChunkGroup encapsulates chunk management logic for an Archetype.
 * It manages chunk arrays, provisioning, and allocation/freeing of entity slots.
 */
public final class ChunkGroup {
    private volatile ArchetypeChunk[] chunks;
    private final AtomicInteger chunkCount = new AtomicInteger(0);
    private final ReentrantLock resizeLock = new ReentrantLock();
    private final ConcurrentLinkedQueue<Integer> availableChunks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger availableCount = new AtomicInteger(0);
    private final AtomicBoolean provisioning = new AtomicBoolean(false);

    private static final int PROVISION_THRESHOLD = 2;
    private static final int SPIN_WAIT_ITERATIONS = 32;

    private final ComponentDescriptor[] descriptors;
    private final long[] elementSizes;
    private final int entitiesPerChunk;
    private final Arena arena;
    private final int managedTypeCount;

    public ChunkGroup(ComponentDescriptor[] descriptors, long[] elementSizes, int entitiesPerChunk, Arena arena, int managedTypeCount) {
        this.descriptors = descriptors;
        this.elementSizes = elementSizes;
        this.entitiesPerChunk = entitiesPerChunk;
        this.arena = arena;
        this.managedTypeCount = managedTypeCount;
        this.chunks = new ArchetypeChunk[Math.max(4, 1)];
        ArchetypeChunk first = new ArchetypeChunk(descriptors, elementSizes, entitiesPerChunk, arena, managedTypeCount);
        this.chunks[0] = first;
        chunkCount.set(1);
        if (first.tryMarkQueued()) {
            this.availableChunks.add(0);
            this.availableCount.incrementAndGet();
        }
    }

    public ArchetypeChunk.ChunkLocation addEntity(int entityId) {
        while (true) {
            ArchetypeChunk.ChunkLocation loc = tryFastPathAllocate(entityId);
            if (loc != null) return loc;
            if (provisioning.compareAndSet(false, true)) {
                try {
                    loc = tryFastPathAllocate(entityId);
                    if (loc != null) return loc;
                    return createChunkAndAllocate(entityId);
                } finally {
                    provisioning.set(false);
                }
            }
            for (int i = 0; i < SPIN_WAIT_ITERATIONS; i++) {
                loc = tryFastPathAllocate(entityId);
                if (loc != null) return loc;
                Thread.onSpinWait();
            }
        }
    }

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
                maybeProvision();
                if (chunk.hasFree() && chunk.tryMarkQueued()) {
                    availableChunks.offer(idxChunk);
                    availableCount.incrementAndGet();
                }
                return new ArchetypeChunk.ChunkLocation(idxChunk, slot);
            }
        }
        return null;
    }

    private ArchetypeChunk.ChunkLocation createChunkAndAllocate(int entityId) {
        ArchetypeChunk newChunk = new ArchetypeChunk(descriptors, elementSizes, entitiesPerChunk, arena, managedTypeCount);
        int newIndex = appendChunk(newChunk);
        int slot = newChunk.allocateSlot(entityId);
        if (newChunk.hasFree() && newChunk.tryMarkQueued()) {
            availableChunks.offer(newIndex);
            availableCount.incrementAndGet();
        }
        return new ArchetypeChunk.ChunkLocation(newIndex, slot);
    }

    public int appendChunk(ArchetypeChunk newChunk) {
        resizeLock.lock();
        try {
            ArchetypeChunk[] arr = this.chunks;
            int idx = chunkCount.get();
            if (idx < arr.length) {
                arr[idx] = newChunk;
                chunkCount.incrementAndGet();
                return idx;
            }
            int newCap = Math.max(4, arr.length << 1);
            ArchetypeChunk[] newArr = new ArchetypeChunk[newCap];
            System.arraycopy(arr, 0, newArr, 0, idx);
            newArr[idx] = newChunk;
            this.chunks = newArr;
            chunkCount.incrementAndGet();
            return idx;
        } finally {
            resizeLock.unlock();
        }
    }

    public void maybeProvision() {
        if (availableCount.get() < PROVISION_THRESHOLD && provisioning.compareAndSet(false, true)) {
            try {
                ArchetypeChunk extra = new ArchetypeChunk(descriptors, elementSizes, entitiesPerChunk, arena, managedTypeCount);
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

    public ArchetypeChunk[] getChunksSnapshot() { return this.chunks; }

    public ArchetypeChunk getChunk(int chunkIndex) {
        ArchetypeChunk[] snap = this.chunks;
        int count = this.chunkCount.get();
        if (chunkIndex < 0 || chunkIndex >= count) throw new IndexOutOfBoundsException();
        return snap[chunkIndex];
    }

    public int chunkCount() { return this.chunkCount.get(); }

    public int getEntityCount() {
        ArchetypeChunk[] snap = this.chunks;
        int count = this.chunkCount.get();
        int total = 0;
        for (int i = 0; i < count; i++) total += snap[i].size();
        return total;
    }

    public List<ArchetypeChunk> getChunks() {
        ArchetypeChunk[] snap = this.chunks;
        int count = this.chunkCount.get();
        List<ArchetypeChunk> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) list.add(snap[i]);
        return list;
    }

    public void forEach(IArchetype.ArchetypeIterator iterator) {
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
}

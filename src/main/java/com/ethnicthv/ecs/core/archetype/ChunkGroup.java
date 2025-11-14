package com.ethnicthv.ecs.core.archetype;

import com.ethnicthv.ecs.core.api.archetype.IArchetype;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ChunkGroup encapsulates chunk management logic for an Archetype.
 * Simplified provisioning logic: only uses resizeLock (no spin / atomic boolean coordination).
 */
public final class ChunkGroup {
    private volatile ArchetypeChunk[] chunks;
    private final AtomicInteger chunkCount = new AtomicInteger(0);
    private final ReentrantLock resizeLock = new ReentrantLock();
    private final ConcurrentLinkedQueue<Integer> availableChunks = new ConcurrentLinkedQueue<>();

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
        }
    }

    // --- New optimistic addEntity implementation ---
    public ArchetypeChunk.ChunkLocation addEntity(int entityId) {
        // 1. Fast path attempt without locking
        ArchetypeChunk.ChunkLocation loc = tryFastPathAllocate(entityId);
        if (loc != null) return loc;

        // 2. Slow path: acquire real lock, double-check, then create
        resizeLock.lock();
        try {
            // 3. Double-check after acquiring lock (another thread may have inserted a chunk)
            loc = tryFastPathAllocate(entityId);
            if (loc != null) return loc;
            // 4. Still no space: create new chunk and allocate
            return createChunkAndAllocate(entityId);
        } finally {
            resizeLock.unlock();
        }
    }

    private ArchetypeChunk.ChunkLocation tryFastPathAllocate(int entityId) {
        Integer idxChunk;
        while ((idxChunk = availableChunks.poll()) != null) { // dequeue a chunk with (expected) free slots
            ArchetypeChunk[] snap = this.chunks;
            int count = this.chunkCount.get();
            if (idxChunk < 0 || idxChunk >= count) continue;
            ArchetypeChunk chunk = snap[idxChunk];
            if (chunk == null) continue;
            chunk.markDequeued();
            int slot = chunk.allocateSlot(entityId);
            if (slot >= 0) {
                if (chunk.hasFree() && chunk.tryMarkQueued()) { // requeue if still space
                    availableChunks.offer(idxChunk);
                }
                return new ArchetypeChunk.ChunkLocation(idxChunk, slot);
            }
            // If allocation failed (race filled chunk), just loop to next available
        }
        return null;
    }

    private ArchetypeChunk.ChunkLocation createChunkAndAllocate(int entityId) {
        ArchetypeChunk newChunk = new ArchetypeChunk(descriptors, elementSizes, entitiesPerChunk, arena, managedTypeCount);
        int newIndex = appendChunk(newChunk); // appendChunk already uses resizeLock (reentrant safe)
        int slot = newChunk.allocateSlot(entityId);
        if (newChunk.hasFree() && newChunk.tryMarkQueued()) {
            availableChunks.offer(newIndex);
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

    public void removeEntity(ArchetypeChunk.ChunkLocation location) {
        int idx = location.chunkIndex;
        ArchetypeChunk[] snap = this.chunks;
        if (idx < 0 || idx >= chunkCount.get()) return;
        ArchetypeChunk chunk = snap[idx];
        if (chunk == null) return;
        chunk.freeSlot(location.indexInChunk);
        if (chunk.hasFree() && chunk.tryMarkQueued()) {
            availableChunks.offer(idx);
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

    // --- Batch APIs ---
    public ArchetypeChunk.ChunkLocation[] addEntities(int[] entityIds) {
        if (entityIds == null || entityIds.length == 0) return new ArchetypeChunk.ChunkLocation[0];
        ArchetypeChunk.ChunkLocation[] out = new ArchetypeChunk.ChunkLocation[entityIds.length];
        int need = entityIds.length;
        int produced = 0;
        // Consume available queued chunks, allocate as many as possible from each before moving on
        Integer idxChunk;
        while (produced < need && (idxChunk = availableChunks.poll()) != null) {
            ArchetypeChunk[] snap = this.chunks;
            int count = this.chunkCount.get();
            if (idxChunk < 0 || idxChunk >= count) continue;
            ArchetypeChunk chunk = snap[idxChunk];
            if (chunk == null) continue;
            chunk.markDequeued();
            while (produced < need) {
                int slot = chunk.allocateSlot(entityIds[produced]);
                if (slot < 0) break;
                out[produced] = new ArchetypeChunk.ChunkLocation(idxChunk, slot);
                produced++;
            }
            if (chunk.hasFree() && chunk.tryMarkQueued()) {
                availableChunks.offer(idxChunk);
            }
        }
        if (produced == need) return out;
        // Slow path: lock and either consume any newly queued chunks or create new chunks
        resizeLock.lock();
        try {
            // Double-check: consume any available after acquiring lock
            while (produced < need && (idxChunk = availableChunks.poll()) != null) {
                ArchetypeChunk[] snap = this.chunks;
                int count = this.chunkCount.get();
                if (idxChunk < 0 || idxChunk >= count) continue;
                ArchetypeChunk chunk = snap[idxChunk];
                if (chunk == null) continue;
                chunk.markDequeued();
                while (produced < need) {
                    int slot = chunk.allocateSlot(entityIds[produced]);
                    if (slot < 0) break;
                    out[produced] = new ArchetypeChunk.ChunkLocation(idxChunk, slot);
                    produced++;
                }
                if (chunk.hasFree() && chunk.tryMarkQueued()) availableChunks.offer(idxChunk);
            }
            // Create new chunks until satisfied
            while (produced < need) {
                ArchetypeChunk newChunk = new ArchetypeChunk(descriptors, elementSizes, entitiesPerChunk, arena, managedTypeCount);
                int newIndex = appendChunk(newChunk); // appendChunk is reentrant with resizeLock
                int toAlloc = Math.min(need - produced, entitiesPerChunk);
                // Fresh chunk yields contiguous indices [0..toAlloc-1]
                for (int i = 0; i < toAlloc; i++) {
                    int slot = newChunk.allocateSlot(entityIds[produced]);
                    out[produced] = new ArchetypeChunk.ChunkLocation(newIndex, slot);
                    produced++;
                }
                if (newChunk.hasFree() && newChunk.tryMarkQueued()) availableChunks.offer(newIndex);
            }
        } finally {
            resizeLock.unlock();
        }
        return out;
    }

    public void removeEntities(ArchetypeChunk.ChunkLocation[] locations) {
        if (locations == null || locations.length == 0) return;
        // Free slots; afterwards enqueue chunks that still have free space
        // Group by chunk to reduce repeated offers; but offering multiple times is harmless due to tryMarkQueued
        for (ArchetypeChunk.ChunkLocation loc : locations) {
            if (loc == null) continue;
            int idx = loc.chunkIndex;
            ArchetypeChunk[] snap = this.chunks;
            int count = this.chunkCount.get();
            if (idx < 0 || idx >= count) continue;
            ArchetypeChunk chunk = snap[idx];
            if (chunk == null) continue;
            chunk.freeSlot(loc.indexInChunk);
            if (chunk.hasFree() && chunk.tryMarkQueued()) availableChunks.offer(idx);
        }
    }

    public ReentrantLock getResizeLock() { return resizeLock; }
}
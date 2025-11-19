package com.ethnicthv.ecs.core.archetype;

import com.ethnicthv.ecs.core.api.archetype.IArchetype;
import com.ethnicthv.ecs.core.api.archetype.IArchetypeChunk;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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

    private final int[] allComponentTypeIds;
    private final int[] managedTypeIds;
    private final ConcurrentHashMap<Integer, Integer> managedIndexMap = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Integer, Integer> componentIndexMap = new ConcurrentHashMap<>();

    // New: per-shared-value grouping of chunks
    private final ConcurrentHashMap<SharedValueKey, ChunkGroup> chunkGroups = new ConcurrentHashMap<>();
    // New: shared component type ids (unmanaged and managed) and their index maps
    private final int[] sharedManagedTypeIds;
    private final int[] sharedUnmanagedTypeIds;
    private final ConcurrentHashMap<Integer, Integer> sharedManagedIndexMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> sharedUnmanagedIndexMap = new ConcurrentHashMap<>();

    // constants preserved
    private static final int CHUNK_SIZE = 16 * 1024;
    private static final int DEFAULT_ENTITIES_PER_CHUNK = 64;

    public Archetype(ComponentMask mask, int[] componentIds, ComponentDescriptor[] descriptors, Arena arena) {
        if (componentIds.length != descriptors.length) {
            throw new IllegalArgumentException("componentIds/descriptors length mismatch");
        }
        this.componentIds = componentIds;
        this.descriptors = descriptors;
        this.componentElementSizes = new long[descriptors.length];
        this.mask = mask;
        this.arena = arena;
        this.allComponentTypeIds = null;
        this.managedTypeIds = new int[0];
        this.sharedManagedTypeIds = new int[0];
        this.sharedUnmanagedTypeIds = new int[0];

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
        // Create default group to preserve previous behavior (one initial chunk)
        getOrCreateChunkGroup(new SharedValueKey(null, null));
    }

    // Managed-aware constructor: unmanaged descriptors + separate managed type ids
    public Archetype(ComponentMask mask, int[] allComponentTypeIds, ComponentDescriptor[] unmanagedDescriptors, int[] managedTypeIds, Arena arena) {
        this.mask = mask;
        this.allComponentTypeIds = allComponentTypeIds;
        this.managedTypeIds = managedTypeIds != null ? managedTypeIds.clone() : new int[0];
        this.componentIds = new int[unmanagedDescriptors.length];
        this.descriptors = unmanagedDescriptors;
        this.componentElementSizes = new long[unmanagedDescriptors.length];
        this.arena = arena;
        this.sharedManagedTypeIds = new int[0];
        this.sharedUnmanagedTypeIds = new int[0];

        long totalPerEntity = 0;
        for (int i = 0; i < unmanagedDescriptors.length; i++) {
            long s = unmanagedDescriptors[i].getTotalSize();
            componentElementSizes[i] = s;
            totalPerEntity += s;
        }
        this.entitiesPerChunk = (totalPerEntity <= 0) ? DEFAULT_ENTITIES_PER_CHUNK : Math.max(1, (int) (CHUNK_SIZE / totalPerEntity));
        // Create default group to preserve previous behavior
        getOrCreateChunkGroup(new SharedValueKey(null, null));
    }

    // New: Fully managed/shared aware constructor
    public Archetype(ComponentMask mask,
                     int[] allComponentTypeIds,
                     ComponentDescriptor[] unmanagedInstanceDescriptors,
                     int[] managedInstanceTypeIds,
                     int[] unmanagedSharedTypeIds,
                     int[] managedSharedTypeIds,
                     Arena arena) {
        this.mask = mask;
        this.allComponentTypeIds = allComponentTypeIds;
        this.managedTypeIds = managedInstanceTypeIds != null ? managedInstanceTypeIds.clone() : new int[0];
        this.sharedUnmanagedTypeIds = unmanagedSharedTypeIds != null ? unmanagedSharedTypeIds.clone() : new int[0];
        this.sharedManagedTypeIds = managedSharedTypeIds != null ? managedSharedTypeIds.clone() : new int[0];
        this.componentIds = new int[unmanagedInstanceDescriptors.length];
        this.descriptors = unmanagedInstanceDescriptors;
        this.componentElementSizes = new long[unmanagedInstanceDescriptors.length];
        this.arena = arena;

        long totalPerEntity = 0;
        for (int i = 0; i < unmanagedInstanceDescriptors.length; i++) {
            long s = unmanagedInstanceDescriptors[i].getTotalSize();
            componentElementSizes[i] = s;
            totalPerEntity += s;
        }
        this.entitiesPerChunk = (totalPerEntity <= 0) ? DEFAULT_ENTITIES_PER_CHUNK : Math.max(1, (int) (CHUNK_SIZE / totalPerEntity));

        // Build index maps for shared type ids
        for (int i = 0; i < this.sharedManagedTypeIds.length; i++) {
            sharedManagedIndexMap.put(this.sharedManagedTypeIds[i], i);
        }
        for (int i = 0; i < this.sharedUnmanagedTypeIds.length; i++) {
            sharedUnmanagedIndexMap.put(this.sharedUnmanagedTypeIds[i], i);
        }
        // Create default group to preserve previous behavior
        getOrCreateChunkGroup(new SharedValueKey(null, null));
    }

    // Internal helper to set unmanaged type ids after construction (used by ArchetypeManager)
    void setUnmanagedTypeIds(int[] unmanagedTypeIds) {
        if (unmanagedTypeIds == null || unmanagedTypeIds.length != this.descriptors.length) {
            throw new IllegalArgumentException("unmanagedTypeIds length mismatch");
        }
        System.arraycopy(unmanagedTypeIds, 0, this.componentIds, 0, unmanagedTypeIds.length);
    }

    /**
     * Get component mask (cached)
     */
    public ComponentMask getMask() { return mask; }

    public int[] getComponentIds() { return componentIds; }

    @Override
    public int[] getComponentTypeIds() {
        return allComponentTypeIds != null ? allComponentTypeIds : getComponentIds();
    }

    public ComponentDescriptor[] getDescriptors() { return descriptors; }

    public int getEntitiesPerChunk() { return entitiesPerChunk; }

    public long[] getElementSizes() { return componentElementSizes; }

    /**
     * Return a snapshot list of chunks. Order is physical array order.
     */
    @Override
    public List<IArchetypeChunk> getChunks() {
        // Backward compatible: if no shared grouping used, return chunks from a default group if present
        ChunkGroup defaultGroup = chunkGroups.getOrDefault(new SharedValueKey(null, null), null);
        if (defaultGroup == null) return List.of();
        List<ArchetypeChunk> list = defaultGroup.getChunks();
        return new ArrayList<>(list);
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
        ChunkGroup defaultGroup = chunkGroups.getOrDefault(new SharedValueKey(null, null), null);
        return defaultGroup != null ? defaultGroup.getChunksSnapshot() : new ArchetypeChunk[0];
    }

    public ArchetypeChunk.ChunkLocation addEntity(int entityId) {
        // Place into default group when no shared key is defined
        ChunkGroup group = getOrCreateChunkGroup(new SharedValueKey(null, null));
        return group.addEntity(entityId);
    }

    public void removeEntity(ArchetypeChunk.ChunkLocation location) {
        // Assume default group for backward compatibility
        ChunkGroup defaultGroup = chunkGroups.get(new SharedValueKey(null, null));
        if (defaultGroup != null) defaultGroup.removeEntity(location);
    }

    public ArchetypeChunk getChunk(int chunkIndex) {
        ChunkGroup defaultGroup = chunkGroups.get(new SharedValueKey(null, null));
        if (defaultGroup == null) throw new IndexOutOfBoundsException();
        return defaultGroup.getChunk(chunkIndex);
    }

    public int chunkCount() {
        ChunkGroup defaultGroup = chunkGroups.get(new SharedValueKey(null, null));
        return defaultGroup != null ? defaultGroup.chunkCount() : 0;
    }

    @Override
    public int getChunkCount() { return chunkCount(); }

    @Override
    public int getEntityCount() {
        ChunkGroup defaultGroup = chunkGroups.get(new SharedValueKey(null, null));
        return defaultGroup != null ? defaultGroup.getEntityCount() : 0;
    }

    /**
     * Iterate over all entities in this archetype.
     * Weakly consistent: concurrent adds/removes may or may not be observed by this traversal,
     * and an entity may be skipped or visited once depending on timing. The traversal never throws
     * due to concurrent modification and aims to be cache-friendly.
     */
    public void forEach(ArchetypeIterator iterator) {
        ChunkGroup defaultGroup = chunkGroups.get(new SharedValueKey(null, null));
        if (defaultGroup == null) return;
        defaultGroup.forEach(iterator);
    }

    /**
     * Get component data for an entity
     */
    public MemorySegment getComponentData(ArchetypeChunk.ChunkLocation location, int componentIndex) {
        ArchetypeChunk[] snap = this.getChunksSnapshot();
        return (location.chunkIndex >= 0 && location.chunkIndex < snap.length)
            ? snap[location.chunkIndex].getComponentData(componentIndex, location.indexInChunk)
            : null;
    }

    /**
     * Set component data for an entity
     */
    public void setComponentData(ArchetypeChunk.ChunkLocation location, int componentIndex, MemorySegment data) {
        ArchetypeChunk[] snap = this.getChunksSnapshot();
        if (location.chunkIndex >= 0 && location.chunkIndex < snap.length) {
            snap[location.chunkIndex].setComponentData(componentIndex, location.indexInChunk, data);
        }
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
            return -1; // not present in this archetype or is managed-only
        });
    }

    // Managed helpers
    public int getManagedTypeIndex(int componentTypeId) {
        if (managedTypeIds.length == 0) return -1;
        return managedIndexMap.computeIfAbsent(componentTypeId, tid -> {
            for (int i = 0; i < managedTypeIds.length; i++) if (managedTypeIds[i] == tid) return i;
            return -1;
        });
    }

    public int[] getManagedTypeIds() { return managedTypeIds; }

    // New APIs for chunk group by shared values
    public ChunkGroup getOrCreateChunkGroup(SharedValueKey key) {
        return chunkGroups.computeIfAbsent(key, k -> new ChunkGroup(descriptors, componentElementSizes, entitiesPerChunk, arena, managedTypeIds.length));
    }

    public ChunkGroup getChunkGroup(SharedValueKey key) {
        return chunkGroups.get(key);
    }

    public int[] getSharedManagedTypeIds() { return sharedManagedTypeIds; }
    public int[] getSharedUnmanagedTypeIds() { return sharedUnmanagedTypeIds; }

    public int getSharedManagedIndex(int componentTypeId) {
        Integer idx = sharedManagedIndexMap.get(componentTypeId);
        return idx == null ? -1 : idx;
    }

    public int getSharedUnmanagedIndex(int componentTypeId) {
        Integer idx = sharedUnmanagedIndexMap.get(componentTypeId);
        return idx == null ? -1 : idx;
    }

    public java.util.Collection<ChunkGroup> getAllChunkGroups() {
        return chunkGroups.values();
    }
}

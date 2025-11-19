package com.ethnicthv.ecs.core.archetype;

import com.ethnicthv.ecs.core.api.archetype.IQuery;
import com.ethnicthv.ecs.core.api.archetype.IQueryBuilder;
import com.ethnicthv.ecs.core.api.archetype.IArchetypeChunk;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.components.ComponentManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/**
 * Query system for filtering archetypes based on component requirements.
 * <p>
 * This class implements both {@link IQueryBuilder} (for configuration) and
 * {@link IQuery} (for execution). Once {@link #build()} is called, it returns
 * an immutable snapshot of the query configuration.
 * <p>
 * Supports:
 * - with(): entities MUST have these components
 * - without(): entities MUST NOT have these components
 * - any(): entities must have AT LEAST ONE of these components
 */
public final class ArchetypeQuery implements IQueryBuilder, IQuery {
    private final ArchetypeWorld world;
    private final ComponentMask.Builder withMask = ComponentMask.builder();
    private final ComponentMask.Builder withoutMask = ComponentMask.builder();
    private final List<ComponentMask> anyMasks = new ArrayList<>();

    private final List<Class<?>> compList = new ArrayList<>();
    private final List<Integer> compIdxList = new ArrayList<>();

    // New: shared filters (at most one managed value and many unmanaged pairs)
    private Object managedSharedFilter = null;
    private final List<UnmanagedFilter> unmanagedSharedFilters = new ArrayList<>();

    // Expose for generated code to reference as a type
    public static final class UnmanagedFilter {
        public final Class<?> type;
        public final long value;
        public UnmanagedFilter(Class<?> type, long value) { this.type = type; this.value = value; }
    }

    public ArchetypeQuery(ArchetypeWorld world) { this.world = world; }

    /**
     * Require entities to have this component
     */
    public <T> ArchetypeQuery with(Class<T> componentClass) {
        Integer componentTypeId = world.getComponentTypeId(componentClass);
        compList.add(componentClass);
        compIdxList.add(componentTypeId);

        if (componentTypeId != null) {
            withMask.with(componentTypeId);
        }
        return this;
    }

    /**
     * Require entities to NOT have this component
     */
    public <T> ArchetypeQuery without(Class<T> componentClass) {
        Integer componentTypeId = world.getComponentTypeId(componentClass);
        if (componentTypeId != null) {
            withoutMask.with(componentTypeId);
        }
        return this;
    }

    /**
     * Require entities to have at least one of the specified components
     */
    @Override
    public ArchetypeQuery any(Class<?>... componentClasses) {
        ComponentMask.Builder anyBuilder = ComponentMask.builder();
        for (Class<?> componentClass : componentClasses) {
            Integer componentTypeId = world.getComponentTypeId(componentClass);
            if (componentTypeId != null) {
                anyBuilder.with(componentTypeId);
            }
        }
        anyMasks.add(anyBuilder.build());
        return this;
    }

    @Override
    public IQueryBuilder withShared(Object managedValue) {
        this.managedSharedFilter = managedValue;
        return this;
    }

    @Override
    public IQueryBuilder withShared(Class<?> unmanagedSharedType, long value) {
        unmanagedSharedFilters.add(new UnmanagedFilter(unmanagedSharedType, value));
        return this;
    }

    /**
     * Build an immutable query from this builder's configuration.
     * <p>
     * This method creates a snapshot of the current query configuration.
     * The returned {@link IQuery} is immutable and thread-safe.
     * <p>
     * Note: Since ArchetypeQuery implements both IQueryBuilder and IQuery,
     * this method simply returns itself. However, callers should treat the
     * returned reference as immutable and not call builder methods on it.
     *
     * @return an immutable query instance
     */
    @Override
    public IQuery build() {
        // For now, we return this instance
        // In a more sophisticated implementation, we could create
        // a truly immutable wrapper or snapshot
        return this;
    }

    /**
     * Execute the query and iterate over matching archetypes
     */
    @Override
    public void forEach(IQuery.ArchetypeConsumer consumer) {
        ComponentMask with = withMask.build();
        ComponentMask without = withoutMask.build();

        for (Archetype archetype : world.getAllArchetypes()) {
            ComponentMask archetypeMask = archetype.getMask();

            // WITH: archetype must contain all required bits
            if (!archetypeMask.containsAll(with)) {
                continue;
            }
            // WITHOUT: archetype must contain none of the excluded bits
            if (!archetypeMask.containsNone(without)) {
                continue;
            }
            // ANY: archetype must intersect at least one any-mask (if present)
            if (!anyMasks.isEmpty()) {
                boolean matchesAny = false;
                for (ComponentMask anyMask : anyMasks) { if (archetypeMask.intersects(anyMask)) { matchesAny = true; break; } }
                if (!matchesAny) continue;
            }

            // Build query key if any shared filters provided
            SharedValueKey key = buildQueryKey(archetype);
            if (key != null) {
                ChunkGroup group = archetype.getChunkGroup(key);
                if (group == null) continue; // skip archetype entirely
            }

            consumer.accept(archetype);
        }
    }

    /**
     * Execute query and iterate over matching chunks
     */
    @Override
    public void forEachChunk(IQuery.ChunkConsumer consumer) {
        // Use group-level filtering if possible
        ComponentMask with = withMask.build();
        ComponentMask without = withoutMask.build();
        for (Archetype archetype : world.getAllArchetypes()) {
            ComponentMask archetypeMask = archetype.getMask();
            if (!archetypeMask.containsAll(with)) continue;
            if (!archetypeMask.containsNone(without)) continue;
            if (!anyMasks.isEmpty()) {
                boolean matchesAny = false;
                for (ComponentMask anyMask : anyMasks) { if (archetypeMask.intersects(anyMask)) { matchesAny = true; break; } }
                if (!matchesAny) continue;
            }
            SharedValueKey key = buildQueryKey(archetype);
            if (key != null) {
                ChunkGroup group = archetype.getChunkGroup(key);
                if (group == null) continue;
                ArchetypeChunk[] chunks = group.getChunksSnapshot();
                int count = group.chunkCount();
                for (int i = 0; i < count; i++) consumer.accept(chunks[i], archetype);
            } else {
                for (IArchetypeChunk chunk : archetype.getChunks()) consumer.accept(chunk, archetype);
            }
        }
    }

    /**
     * Execute query and iterate over matching entities
     */
    @Override
    public void forEachEntity(IQuery.EntityConsumer consumer) {
        ComponentManager mgr = world.getComponentManager();
        Class<?>[] componentClasses = new Class<?>[compList.size()];
        for (int i = 0; i < compList.size(); i++) componentClasses[i] = compList.get(i);

        ComponentMask with = withMask.build();
        ComponentMask without = withoutMask.build();

        for (Archetype archetype : world.getAllArchetypes()) {
            ComponentMask archetypeMask = archetype.getMask();
            if (!archetypeMask.containsAll(with)) continue;
            if (!archetypeMask.containsNone(without)) continue;
            if (!anyMasks.isEmpty()) {
                boolean matchesAny = false;
                for (ComponentMask anyMask : anyMasks) { if (archetypeMask.intersects(anyMask)) { matchesAny = true; break; } }
                if (!matchesAny) continue;
            }

            // Precompute component indices for this archetype
            int[] compIndices = new int[compIdxList.size()];
            boolean ok = true;
            for (int i = 0; i < compIdxList.size(); i++) {
                int idx = archetype.indexOfComponentType(compIdxList.get(i));
                if (idx < 0) { ok = false; break; }
                compIndices[i] = idx;
            }
            if (!ok) continue;

            SharedValueKey key = buildQueryKey(archetype);
            if (key != null) {
                ChunkGroup group = archetype.getChunkGroup(key);
                if (group == null) continue;
                ArchetypeChunk[] chunks = group.getChunksSnapshot();
                int count = group.chunkCount();
                for (int ci = 0; ci < count; ci++) {
                    ArchetypeChunk chunk = chunks[ci];
                    iterateChunkEntities(chunk, mgr, componentClasses, compIndices, consumer, archetype);
                }
            } else {
                for (IArchetypeChunk chunk : archetype.getChunks()) {
                    iterateChunkEntities((ArchetypeChunk) chunk, mgr, componentClasses, compIndices, consumer, archetype);
                }
            }
        }
    }

    private void iterateChunkEntities(ArchetypeChunk chunk, ComponentManager mgr, Class<?>[] componentClasses, int[] compIndices, IQuery.EntityConsumer consumer, Archetype archetype) {
        ComponentHandle[] pooled = new ComponentHandle[compIndices.length];
        for (int k = 0; k < compIndices.length; k++) pooled[k] = mgr.acquireHandle();
        try {
            int idx = chunk.nextOccupiedIndex(0);
            while (idx >= 0) {
                int entityId = chunk.getEntityId(idx);
                for (int k = 0; k < compIndices.length; k++) {
                    var seg = chunk.getComponentData(compIndices[k], idx);
                    pooled[k].reset(seg, mgr.getDescriptor(componentClasses[k]));
                }
                consumer.accept(entityId, pooled, archetype);
                idx = chunk.nextOccupiedIndex(idx + 1);
            }
        } finally {
            for (int k = 0; k < compIndices.length; k++) if (pooled[k] != null) mgr.releaseHandle(pooled[k]);
        }
    }

    /**
     * Count matching entities
     */
    @Override
    public int count() {
        final int[] count = {0};
        forEach(archetype -> count[0] += archetype.getEntityCount());
        return count[0];
    }

    /**
     * Execute the query and process matching entities in parallel across multiple CPU cores.
     * <p>
     * This method leverages Java's parallel streams to distribute entity processing across
     * available CPU cores. The processing is done at the chunk level - each chunk is processed
     * by a single thread, but different chunks may be processed concurrently.
     * <p>
     * <strong>THREAD SAFETY REQUIREMENTS:</strong>
     * <ul>
     *   <li>The provided {@code EntityConsumer} MUST be thread-safe</li>
     *   <li>Any shared state accessed or modified by the consumer must be properly synchronized</li>
     *   <li>The consumer may be called concurrently from multiple threads</li>
     *   <li>There are no ordering guarantees - entities may be processed in any order</li>
     * </ul>
     * <p>
     * Performance considerations:
     * <ul>
     *   <li>Best suited for CPU-intensive operations on large entity sets</li>
     *   <li>Overhead of parallelization may not be worth it for very small entity counts</li>
     *   <li>The actual parallelism depends on the ForkJoinPool common pool size</li>
     * </ul>
     *
     * @param consumer A thread-safe callback that processes each matching entity.
     *                 Called with (entityId, handles, archetype) for each entity.
     * @throws NullPointerException if consumer is null
     *
     * @see #forEachEntity(EntityConsumer) for sequential processing
     */
    @Override
    public void forEachParallel(IQuery.EntityConsumer consumer) {
        if (consumer == null) throw new NullPointerException("EntityConsumer must not be null");
        ComponentManager mgr = world.getComponentManager();
        Class<?>[] componentClasses = new Class<?>[compList.size()];
        for (int i = 0; i < compList.size(); i++) componentClasses[i] = compList.get(i);

        ComponentMask with = withMask.build();
        ComponentMask without = withoutMask.build();

        for (Archetype archetype : world.getAllArchetypes()) {
            ComponentMask archetypeMask = archetype.getMask();
            if (!archetypeMask.containsAll(with)) continue;
            if (!archetypeMask.containsNone(without)) continue;
            if (!anyMasks.isEmpty()) {
                boolean matchesAny = false;
                for (ComponentMask anyMask : anyMasks) { if (archetypeMask.intersects(anyMask)) { matchesAny = true; break; } }
                if (!matchesAny) continue;
            }

            int[] compIndices = new int[compIdxList.size()];
            boolean ok = true;
            for (int i = 0; i < compIdxList.size(); i++) {
                int idx = archetype.indexOfComponentType(compIdxList.get(i));
                if (idx < 0) { ok = false; break; }
                compIndices[i] = idx;
            }
            if (!ok) continue;

            SharedValueKey key = buildQueryKey(archetype);
            if (key != null) {
                ChunkGroup group = archetype.getChunkGroup(key);
                if (group == null) continue;
                ArchetypeChunk[] chunks = group.getChunksSnapshot();
                int count = group.chunkCount();
                Arrays.stream(chunks, 0, count).parallel().forEach(chunk ->
                    iterateChunkEntities(chunk, mgr, componentClasses, compIndices, consumer, archetype)
                );
            } else {
                ArchetypeChunk[] chunks = archetype.getChunksSnapshot();
                int count = archetype.chunkCount();
                Arrays.stream(chunks, 0, count).parallel().forEach(chunk ->
                    iterateChunkEntities(chunk, mgr, componentClasses, compIndices, consumer, archetype)
                );
            }
        }
    }

    private SharedValueKey buildQueryKey(Archetype archetype) {
        int[] managedIdx = null;
        long[] unmanagedVals = null;
        boolean any = false;

        if (managedSharedFilter != null) {
            int ticket = world.findSharedIndex(managedSharedFilter);
            if (ticket < 0) return null;
            int managedCount = archetype.getSharedManagedTypeIds().length;
            if (managedCount == 0) return null;
            managedIdx = new int[managedCount];
            Arrays.fill(managedIdx, -1);
            for (int typeId : archetype.getSharedManagedTypeIds()) {
                int pos = archetype.getSharedManagedIndex(typeId);
                if (pos >= 0) { managedIdx[pos] = ticket; any = true; }
            }
        }
        if (!unmanagedSharedFilters.isEmpty()) {
            int unmanagedCount = archetype.getSharedUnmanagedTypeIds().length;
            if (unmanagedCount == 0) return null;
            unmanagedVals = new long[unmanagedCount];
            Arrays.fill(unmanagedVals, Long.MIN_VALUE);
            for (UnmanagedFilter f : unmanagedSharedFilters) {
                Integer typeId = world.getComponentTypeId(f.type);
                if (typeId == null) return null;
                int pos = archetype.getSharedUnmanagedIndex(typeId);
                if (pos < 0) return null;
                unmanagedVals[pos] = f.value; any = true;
            }
        }
        if (!any) return null;
        return new SharedValueKey(managedIdx, unmanagedVals);
    }
}

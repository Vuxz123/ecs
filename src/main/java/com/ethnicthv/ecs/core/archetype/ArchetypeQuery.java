package com.ethnicthv.ecs.core.archetype;

import com.ethnicthv.ecs.core.api.archetype.IQuery;
import com.ethnicthv.ecs.core.api.archetype.IQueryBuilder;
import com.ethnicthv.ecs.core.api.archetype.IArchetype;
import com.ethnicthv.ecs.core.api.archetype.IArchetypeChunk;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.components.ComponentManager;

import java.util.ArrayList;
import java.util.List;

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

    public ArchetypeQuery(ArchetypeWorld world) {
        this.world = world;
    }

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
                for (ComponentMask anyMask : anyMasks) {
                    if (archetypeMask.intersects(anyMask)) { matchesAny = true; break; }
                }
                if (!matchesAny) continue;
            }

            consumer.accept(archetype);
        }
    }

    /**
     * Execute query and iterate over matching chunks
     */
    @Override
    public void forEachChunk(IQuery.ChunkConsumer consumer) {
        forEach(archetype -> {
            for (IArchetypeChunk chunk : archetype.getChunks()) {
                consumer.accept(chunk, archetype);
            }
        });
    }

    /**
     * Execute query and iterate over matching entities
     */
    @Override
    public void forEachEntity(IQuery.EntityConsumer consumer) {
        ComponentManager mgr = world.getComponentManager();

        forEach(archetype -> {
            // Compute component indices for this archetype using its internal cache
            Class<?>[] componentClasses = new Class<?>[compList.size()];
            for (int i = 0; i < compList.size(); i++) {
                componentClasses[i] = compList.get(i);
            }
            int[] compIndices = new int[compIdxList.size()];
            for (int i = 0; i < compIdxList.size(); i++) {
                int idx = archetype.indexOfComponentType(compIdxList.get(i));
                if (idx < 0) {
                    return;
                }
                compIndices[i] = idx;
            }
            archetype.forEach((entityId, location, chunk) -> {
                ComponentManager.BoundHandle[] bound = new ComponentManager.BoundHandle[componentClasses.length];
                ComponentHandle[] handles = new ComponentHandle[componentClasses.length];
                try {
                    for (int k = 0; k < compIndices.length; k++) {
                        int compIdx = compIndices[k];
                        var seg = chunk.getComponentData(compIdx, location.indexInChunk);
                        bound[k] = mgr.acquireBoundHandle(componentClasses[k], seg);
                        handles[k] = bound[k].handle();
                    }

                    consumer.accept(entityId, handles, archetype);
                } finally {
                    for (ComponentManager.BoundHandle boundHandle : bound) {
                        if (boundHandle != null) {
                            try { boundHandle.close(); } catch (Exception ignored) {}
                        }
                    }
                }
            });
        });
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
        if (consumer == null) {
            throw new NullPointerException("EntityConsumer must not be null");
        }

        record WorkItem(IArchetype archetype, ArchetypeChunk chunk, int entityId, int elementIndex) {}

        List<WorkItem> tasks = new ArrayList<>();

        // Build flat list of work items across all matching chunks
        forEachChunk((ichunk, archetype) -> {
            // Cast to concrete chunk to leverage fast occupied-iteration helpers
            ArchetypeChunk chunk = (ArchetypeChunk) ichunk;
            int idx = chunk.nextOccupiedIndex(0);
            while (idx >= 0) {
                int entityId = chunk.getEntityId(idx);
                tasks.add(new WorkItem(archetype, chunk, entityId, idx));
                idx = chunk.nextOccupiedIndex(idx + 1);
            }
        });

        ComponentManager mgr = world.getComponentManager();
        Class<?>[] componentClasses = new Class<?>[compList.size()];
        for (int i = 0; i < compList.size(); i++) {
            componentClasses[i] = compList.get(i);
        }

        tasks.parallelStream().forEach(item -> {
            ArchetypeChunk chunk = item.chunk();
            int elementIndex = item.elementIndex();

            int[] compIndices = new int[compIdxList.size()];
            for (int i = 0; i < compIdxList.size(); i++) {
                int idx = item.archetype().indexOfComponentType(compIdxList.get(i));
                if (idx < 0) {
                    return; // Component not found in this archetype
                }
                compIndices[i] = idx;
            }

            ComponentManager.BoundHandle[] bound = new ComponentManager.BoundHandle[componentClasses.length];
            ComponentHandle[] handles = new ComponentHandle[componentClasses.length];
            try {
                for (int k = 0; k < compIndices.length; k++) {
                    int compIdx = compIndices[k];
                    var seg = chunk.getComponentData(compIdx, elementIndex);
                    bound[k] = mgr.acquireBoundHandle(componentClasses[k], seg);
                    handles[k] = bound[k].handle();
                }
                consumer.accept(item.entityId(), handles, item.archetype());
            } finally {
                for (ComponentManager.BoundHandle boundHandle : bound) {
                    if (boundHandle != null) {
                        try { boundHandle.close(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }
}

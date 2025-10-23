package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.ComponentHandle;
import com.ethnicthv.ecs.core.ComponentManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Query system for filtering archetypes based on component requirements.
 *
 * Supports:
 * - with(): entities MUST have these components
 * - without(): entities MUST NOT have these components
 * - any(): entities must have AT LEAST ONE of these components
 */
public final class ArchetypeQuery {
    private final ArchetypeWorld world;
    private final ComponentMask.Builder withMask = ComponentMask.builder();
    private final ComponentMask.Builder withoutMask = ComponentMask.builder();
    private final List<ComponentMask> anyMasks = new ArrayList<>();

    public ArchetypeQuery(ArchetypeWorld world) {
        this.world = world;
    }

    /**
     * Require entities to have this component
     */
    public <T> ArchetypeQuery with(Class<T> componentClass) {
        Integer componentTypeId = world.getComponentTypeId(componentClass);
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
    @SafeVarargs
    public final ArchetypeQuery any(Class<?>... componentClasses) {
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
     * Execute the query and iterate over matching archetypes
     */
    public void forEach(ArchetypeConsumer consumer) {
        ComponentMask with = withMask.build();
        ComponentMask without = withoutMask.build();

        for (Archetype archetype : world.getAllArchetypes()) {
            ComponentMask archetypeMask = archetype.getMask();

            // Check WITH requirements
            if (!archetypeMask.contains(with)) {
                continue;
            }

            // Check WITHOUT requirements
            boolean hasExcluded = false;
            for (int i = 0; i < 64; i++) {
                if (without.has(i) && archetypeMask.has(i)) {
                    hasExcluded = true;
                    break;
                }
            }
            if (hasExcluded) {
                continue;
            }

            // Check ANY requirements
            if (!anyMasks.isEmpty()) {
                boolean matchesAny = false;
                for (ComponentMask anyMask : anyMasks) {
                    for (int i = 0; i < 64; i++) {
                        if (anyMask.has(i) && archetypeMask.has(i)) {
                            matchesAny = true;
                            break;
                        }
                    }
                    if (matchesAny) break;
                }
                if (!matchesAny) {
                    continue;
                }
            }

            // Archetype matches query - iterate over its entities
            consumer.accept(archetype);
        }
    }

    /**
     * Execute query and iterate over matching chunks
     */
    public void forEachChunk(ChunkConsumer consumer) {
        forEach(archetype -> {
            for (ArchetypeChunk chunk : archetype.getChunks()) {
                consumer.accept(chunk, archetype);
            }
        });
    }

    /**
     * Execute query and iterate over matching entities
     */
    public void forEachEntity(EntityConsumer consumer) {
        forEach(archetype -> {
            archetype.forEach((entityId, location, chunk) -> {
                consumer.accept(entityId, location, chunk, archetype);
            });
        });
    }

    /**
     * Execute query and iterate over matching entities, providing pooled ComponentHandles for the requested component classes.
     * The consumer receives an array of bound ComponentHandle (same order as componentClasses). The handles are released
     * back to the manager after the consumer returns. This is intended for synchronous, short-lived access inside the consumer.
     */
    @SafeVarargs
    public final void forEachEntityWith(EntityWithHandlesConsumer consumer, Class<?>... componentClasses) {
        // Resolve component type IDs for requested classes
        Integer[] reqTypeIds = new Integer[componentClasses.length];
        for (int i = 0; i < componentClasses.length; i++) {
            reqTypeIds[i] = world.getComponentTypeId(componentClasses[i]);
            if (reqTypeIds[i] == null) {
                // If a requested component class is not registered, no entity will match; return early
                return;
            }
        }

        ComponentManager mgr = world.getComponentManager();

        forEach(archetype -> {
            int[] archCompIds = archetype.getComponentIds();
            // For each requested type, find index inside archetype or -1
            int[] compIndices = new int[reqTypeIds.length];
            boolean allPresent = true;
            for (int i = 0; i < reqTypeIds.length; i++) {
                int tid = reqTypeIds[i];
                int idx = -1;
                for (int j = 0; j < archCompIds.length; j++) {
                    if (archCompIds[j] == tid) { idx = j; break; }
                }
                compIndices[i] = idx;
                if (idx < 0) { allPresent = false; break; }
            }
            if (!allPresent) return; // this archetype doesn't contain all requested components

            // Iterate entities in archetype
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

                    consumer.accept(entityId, handles, location, archetype);
                } finally {
                    for (int k = 0; k < bound.length; k++) {
                        if (bound[k] != null) {
                            try { bound[k].close(); } catch (Exception ignored) {}
                        }
                    }
                }
            });
        });
    }

    /**
     * Count matching entities
     */
    public int count() {
        final int[] count = {0};
        forEach(archetype -> count[0] += archetype.getEntityCount());
        return count[0];
    }

    @FunctionalInterface
    public interface ArchetypeConsumer {
        void accept(Archetype archetype);
    }

    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(ArchetypeChunk chunk, Archetype archetype);
    }

    @FunctionalInterface
    public interface EntityConsumer {
        void accept(int entityId, ArchetypeChunk.ChunkLocation location, ArchetypeChunk chunk, Archetype archetype);
    }

    @FunctionalInterface
    public interface EntityWithHandlesConsumer {
        void accept(int entityId, ComponentHandle[] handles, ArchetypeChunk.ChunkLocation location, Archetype archetype);
    }
}

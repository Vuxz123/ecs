package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.ComponentDescriptor;
import com.ethnicthv.ecs.core.ComponentManager;

import java.lang.foreign.Arena;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Manages all archetypes in the ECS world.
 * Creates and retrieves archetypes based on component masks.
 */
public final class ArchetypeManager {
    private final ConcurrentHashMap<ComponentMask, Archetype> archetypes;
    private final Arena arena;
    private final ComponentManager componentManager;
    private final IntFunction<ArchetypeWorld.ComponentMetadata> metadataProvider;

    public ArchetypeManager(Arena arena, ComponentManager componentManager,
                            IntFunction<ArchetypeWorld.ComponentMetadata> metadataProvider) {
        this.archetypes = new ConcurrentHashMap<>();
        this.arena = arena;
        this.componentManager = componentManager;
        this.metadataProvider = metadataProvider;
    }

    /**
     * Get or create an archetype for the given component mask.
     * Thread-safe: computeIfAbsent is atomic per key on ConcurrentHashMap.
     * This overload derives componentIds and descriptors internally from the mask.
     */
    public Archetype getOrCreateArchetype(ComponentMask mask) {
        return archetypes.computeIfAbsent(mask, m -> {
            int[] componentIds = m.toComponentIdArray();
            ComponentDescriptor[] descriptors = new ComponentDescriptor[componentIds.length];
            for (int i = 0; i < componentIds.length; i++) {
                var meta = metadataProvider.apply(componentIds[i]);
                if (meta == null) {
                    throw new IllegalStateException("Component metadata missing for id=" + componentIds[i]);
                }
                descriptors[i] = componentManager.getDescriptor(meta.type());
            }
            return new Archetype(m, componentIds, descriptors, arena);
        });
    }

    /**
     * Get an existing archetype or null if it doesn't exist
     */
    public Archetype getArchetype(ComponentMask mask) {
        return archetypes.get(mask);
    }

    /**
     * Get all archetypes. The returned view is weakly consistent and safe to iterate while
     * other threads add new archetypes; it will not throw ConcurrentModificationException.
     */
    public Iterable<Archetype> getAllArchetypes() {
        return archetypes.values();
    }

    /**
     * Get total entity count across all archetypes
     */
    public int getTotalEntityCount() {
        return archetypes.values().stream()
            .mapToInt(Archetype::getEntityCount)
            .sum();
    }

    /**
     * Clear all archetypes
     */
    public void clear() {
        archetypes.clear();
    }
}

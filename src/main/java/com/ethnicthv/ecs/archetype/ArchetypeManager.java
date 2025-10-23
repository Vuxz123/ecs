package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.ComponentDescriptor;

import java.lang.foreign.Arena;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages all archetypes in the ECS world.
 * Creates and retrieves archetypes based on component masks.
 */
public final class ArchetypeManager {
    private final Map<ComponentMask, Archetype> archetypes;
    private final Arena arena;

    public ArchetypeManager(Arena arena) {
        this.archetypes = new HashMap<>();
        this.arena = arena;
    }

    /**
     * Get or create an archetype for the given component mask
     * Now accepts ComponentDescriptor[] (aligned with ComponentManager).
     */
    public Archetype getOrCreateArchetype(ComponentMask mask, int[] componentIds, ComponentDescriptor[] descriptors) {
        return archetypes.computeIfAbsent(mask,
            m -> new Archetype(m, componentIds, descriptors, arena));
    }

    /**
     * Get an existing archetype or null if it doesn't exist
     */
    public Archetype getArchetype(ComponentMask mask) {
        return archetypes.get(mask);
    }

    /**
     * Get all archetypes
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

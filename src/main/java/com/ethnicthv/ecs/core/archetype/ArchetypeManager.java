package com.ethnicthv.ecs.core.archetype;

import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import com.ethnicthv.ecs.core.components.ComponentManager;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;
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
            int[] allTypeIds = m.toComponentIdArray();

            List<ComponentDescriptor> unmanagedInstanceDescs = new ArrayList<>();
            List<Integer> unmanagedInstanceIds = new ArrayList<>();
            List<Integer> managedInstanceIds = new ArrayList<>();
            List<Integer> unmanagedSharedIds = new ArrayList<>();
            List<Integer> managedSharedIds = new ArrayList<>();

            for (int typeId : allTypeIds) {
                var meta = metadataProvider.apply(typeId);
                if (meta == null) {
                    throw new IllegalStateException("Component metadata missing for id=" + typeId);
                }
                ComponentDescriptor desc = componentManager.getDescriptor(meta.type());
                if (desc == null) {
                    throw new IllegalStateException("Descriptor missing for component " + meta.type().getName());
                }
                ComponentDescriptor.ComponentKind kind = desc.getKind();
                switch (kind) {
                    case INSTANCE_UNMANAGED -> {
                        unmanagedInstanceIds.add(typeId);
                        unmanagedInstanceDescs.add(desc);
                    }
                    case INSTANCE_MANAGED -> managedInstanceIds.add(typeId);
                    case SHARED_UNMANAGED -> unmanagedSharedIds.add(typeId);
                    case SHARED_MANAGED -> managedSharedIds.add(typeId);
                }
            }

            ComponentDescriptor[] unmanagedInstanceArray = unmanagedInstanceDescs.toArray(new ComponentDescriptor[0]);
            int[] managedInstanceIdsArray = managedInstanceIds.stream().mapToInt(Integer::intValue).toArray();
            int[] unmanagedSharedIdsArray = unmanagedSharedIds.stream().mapToInt(Integer::intValue).toArray();
            int[] managedSharedIdsArray = managedSharedIds.stream().mapToInt(Integer::intValue).toArray();

            Archetype archetype = new Archetype(m, allTypeIds, unmanagedInstanceArray, managedInstanceIdsArray, unmanagedSharedIdsArray, managedSharedIdsArray, arena);
            // Provide the mapping for unmanaged instance type ids order used in this archetype
            int[] unmanagedInstanceIdsArray = unmanagedInstanceIds.stream().mapToInt(Integer::intValue).toArray();
            archetype.setUnmanagedTypeIds(unmanagedInstanceIdsArray);
            return archetype;
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

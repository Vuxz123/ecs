package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.ComponentDescriptor;
import com.ethnicthv.ecs.core.ComponentManager;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.*;

/**
 * ArchetypeWorld - Main ECS World using Archetype pattern
 *
 * Manages entities, components, and archetypes for cache-friendly data access.
 * Entities with the same component composition are grouped together in archetypes.
 */
public final class ArchetypeWorld implements AutoCloseable {
    private final ArchetypeManager archetypeManager;
    private final ComponentManager componentManager;
    private final Map<Integer, EntityRecord> entityRecords; // entityId -> location in archetype
    private final Map<Class<?>, Integer> componentTypeIds;
    private final Map<Integer, ComponentMetadata> componentMetadata;
    private final Arena arena;
    private int nextEntityId = 1;
    private int nextComponentTypeId = 0;

    public ArchetypeWorld(ComponentManager componentManager) {
        this.arena = Arena.ofShared();
        this.archetypeManager = new ArchetypeManager(arena);
        this.componentManager = componentManager;
        this.entityRecords = new HashMap<>();
        this.componentTypeIds = new HashMap<>();
        this.componentMetadata = new HashMap<>();
    }

    /**
     * Register a component type via ComponentManager
     */
    public <T> int registerComponent(Class<T> componentClass) {
        int id = componentTypeIds.computeIfAbsent(componentClass, c -> {
            int tid = componentManager.registerComponent(componentClass);
            // store metadata from descriptor
            ComponentDescriptor desc = componentManager.getDescriptor(componentClass);
            componentMetadata.put(tid, new ComponentMetadata(tid, componentClass, desc.getTotalSize()));
            // update nextComponentTypeId to reflect assigned id
            nextComponentTypeId = Math.max(nextComponentTypeId, tid + 1);
            return tid;
        });
        return id;
    }

    /**
     * Create a new entity
     */
    public int createEntity() {
        int entityId = nextEntityId++;
        // Start with empty archetype (no components)
        ComponentMask emptyMask = new ComponentMask();
        Archetype archetype = archetypeManager.getOrCreateArchetype(emptyMask, new int[0], new ComponentDescriptor[0]);
        ArchetypeChunk.ChunkLocation location = archetype.addEntity(entityId);
        entityRecords.put(entityId, new EntityRecord(archetype, location, emptyMask));
        return entityId;
    }

    /**
     * Add a component to an entity
     */
    public <T> void addComponent(int entityId, Class<T> componentClass, MemorySegment data) {
        EntityRecord record = entityRecords.get(entityId);
        if (record == null) {
            throw new IllegalArgumentException("Entity " + entityId + " does not exist");
        }

        Integer componentTypeId = componentTypeIds.get(componentClass);
        if (componentTypeId == null) {
            throw new IllegalArgumentException("Component type " + componentClass + " not registered");
        }

        // Create new mask with the additional component
        ComponentMask newMask = record.mask.set(componentTypeId);

        // Move entity to new archetype
        moveEntityToArchetype(entityId, record, newMask);

        // Set component data
        EntityRecord newRecord = entityRecords.get(entityId);
        int componentIndex = findComponentIndex(newRecord.archetype.getComponentIds(), componentTypeId);
        newRecord.archetype.setComponentData(newRecord.location, componentIndex, data);
    }

    /**
     * Remove a component from an entity
     */
    public <T> void removeComponent(int entityId, Class<T> componentClass) {
        EntityRecord record = entityRecords.get(entityId);
        if (record == null) {
            throw new IllegalArgumentException("Entity " + entityId + " does not exist");
        }

        Integer componentTypeId = componentTypeIds.get(componentClass);
        if (componentTypeId == null || !record.mask.has(componentTypeId)) {
            return; // Component doesn't exist on this entity
        }

        // Create new mask without the component
        ComponentMask newMask = record.mask.clear(componentTypeId);

        // Move entity to new archetype
        moveEntityToArchetype(entityId, record, newMask);
    }

    /**
     * Get component data for an entity
     */
    public <T> MemorySegment getComponent(int entityId, Class<T> componentClass) {
        EntityRecord record = entityRecords.get(entityId);
        if (record == null) {
            return null;
        }

        Integer componentTypeId = componentTypeIds.get(componentClass);
        if (componentTypeId == null || !record.mask.has(componentTypeId)) {
            return null;
        }

        int componentIndex = findComponentIndex(record.archetype.getComponentIds(), componentTypeId);
        return record.archetype.getComponentData(record.location, componentIndex);
    }

    /**
     * Check if entity has a component
     */
    public <T> boolean hasComponent(int entityId, Class<T> componentClass) {
        EntityRecord record = entityRecords.get(entityId);
        if (record == null) {
            return false;
        }

        Integer componentTypeId = componentTypeIds.get(componentClass);
        return componentTypeId != null && record.mask.has(componentTypeId);
    }

    /**
     * Destroy an entity
     */
    public void destroyEntity(int entityId) {
        EntityRecord record = entityRecords.remove(entityId);
        if (record != null) {
            record.archetype.removeEntity(record.location);
        }
    }

    /**
     * Create a query for entities matching component requirements
     */
    public ArchetypeQuery query() {
        return new ArchetypeQuery(this);
    }

    /**
     * Get all archetypes
     */
    public Iterable<Archetype> getAllArchetypes() {
        return archetypeManager.getAllArchetypes();
    }

    /**
     * Get total entity count
     */
    public int getEntityCount() {
        return entityRecords.size();
    }

    /**
     * Get component type ID
     */
    public Integer getComponentTypeId(Class<?> componentClass) {
        return componentTypeIds.get(componentClass);
    }

    /**
     * Get component metadata
     */
    public ComponentMetadata getComponentMetadata(int componentTypeId) {
        return componentMetadata.get(componentTypeId);
    }

    /**
     * Close the world and release resources
     */
    public void close() {
        arena.close();
    }

    public ComponentManager getComponentManager() {
        return componentManager;
    }

    // ============ Internal Methods ============

    private void moveEntityToArchetype(int entityId, EntityRecord oldRecord, ComponentMask newMask) {
        // Build component arrays info for new archetype
        List<Integer> componentIdsList = new ArrayList<>();

        for (int i = 0; i < nextComponentTypeId; i++) {
            if (newMask.has(i)) {
                componentIdsList.add(i);
            }
        }

        int[] componentIds = componentIdsList.stream().mapToInt(Integer::intValue).toArray();

        // Build ComponentDescriptor[] for these ids
        ComponentDescriptor[] descriptors = new ComponentDescriptor[componentIds.length];
        for (int i = 0; i < componentIds.length; i++) {
            ComponentMetadata meta = componentMetadata.get(componentIds[i]);
            if (meta == null) {
                throw new IllegalStateException("Component metadata missing for id=" + componentIds[i]);
            }
            descriptors[i] = componentManager.getDescriptor(meta.type);
        }

        // Get or create new archetype
        Archetype newArchetype = archetypeManager.getOrCreateArchetype(newMask, componentIds, descriptors);

        // Copy existing component data
        ArchetypeChunk.ChunkLocation newLocation = newArchetype.addEntity(entityId);

        // Only copy components that exist in BOTH old and new archetypes
        int[] oldComponentIds = oldRecord.archetype.getComponentIds();
        for (int newIdx = 0; newIdx < componentIds.length; newIdx++) {
            int componentTypeId = componentIds[newIdx];
            if (oldRecord.mask.has(componentTypeId)) {
                // Find this component's index in the OLD archetype
                int oldIdx = -1;
                for (int j = 0; j < oldComponentIds.length; j++) {
                    if (oldComponentIds[j] == componentTypeId) {
                        oldIdx = j;
                        break;
                    }
                }

                if (oldIdx >= 0) {
                    MemorySegment oldData = oldRecord.archetype.getComponentData(oldRecord.location, oldIdx);
                    if (oldData != null) {
                        newArchetype.setComponentData(newLocation, newIdx, oldData);
                    }
                }
            }
        }

        // Remove from old archetype
        oldRecord.archetype.removeEntity(oldRecord.location);

        // Update entity record
        entityRecords.put(entityId, new EntityRecord(newArchetype, newLocation, newMask));
    }

    /**
     * Find the index of a component type ID in the archetype's component array
     */
    private int findComponentIndex(int[] componentIds, int componentTypeId) {
        for (int i = 0; i < componentIds.length; i++) {
            if (componentIds[i] == componentTypeId) {
                return i;
            }
        }
        return -1;
    }

    // ============ Internal Records ============

    record EntityRecord(Archetype archetype, ArchetypeChunk.ChunkLocation location, ComponentMask mask) {}

    public record ComponentMetadata(int id, Class<?> type, long size) {}
}

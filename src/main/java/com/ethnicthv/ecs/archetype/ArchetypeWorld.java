package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.ComponentDescriptor;
import com.ethnicthv.ecs.core.ComponentManager;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ArchetypeWorld - Main ECS World using Archetype pattern
 * <p>
 * Manages entities, components, and archetypes for cache-friendly data access.
 * Entities with the same component composition are grouped together in archetypes.
 */
public final class ArchetypeWorld implements AutoCloseable {
    private final ArchetypeManager archetypeManager;
    private final ComponentManager componentManager;
    private final ConcurrentHashMap<Integer, EntityRecord> entityRecords; // entityId -> location in archetype
    private final ConcurrentHashMap<Class<?>, Integer> componentTypeIds;
    private final ConcurrentHashMap<Integer, ComponentMetadata> componentMetadata;
    private final Arena arena;
    private final AtomicInteger nextEntityId = new AtomicInteger(1);
    private final AtomicInteger nextComponentTypeId = new AtomicInteger(0);

    public ArchetypeWorld(ComponentManager componentManager) {
        this.arena = Arena.ofShared();
        this.componentManager = componentManager;
        this.entityRecords = new ConcurrentHashMap<>();
        this.componentTypeIds = new ConcurrentHashMap<>();
        this.componentMetadata = new ConcurrentHashMap<>();
        // Initialize ArchetypeManager after metadata map is ready
        this.archetypeManager = new ArchetypeManager(arena, componentManager, this::getComponentMetadata);
    }

    /**
     * Register a component type via ComponentManager
     */
    public <T> int registerComponent(Class<T> componentClass) {
        return componentTypeIds.computeIfAbsent(componentClass, cls -> {
            int tid = componentManager.registerComponent(cls);
            // store metadata from descriptor
            ComponentDescriptor desc = componentManager.getDescriptor(cls);
            componentMetadata.put(tid, new ComponentMetadata(tid, cls, desc.getTotalSize()));
            // update nextComponentTypeId to reflect assigned id atomically
            nextComponentTypeId.updateAndGet(prev -> Math.max(prev, tid + 1));
            return tid;
        });
    }

    /**
     * Create a new entity
     */
    public int createEntity() {
        int entityId = nextEntityId.getAndIncrement();
        // Start with empty archetype (no components)
        ComponentMask emptyMask = new ComponentMask();
        Archetype archetype = archetypeManager.getOrCreateArchetype(emptyMask);
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
        int componentIndex = newRecord.archetype.indexOfComponentType(componentTypeId);
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

        int componentIndex = record.archetype.indexOfComponentType(componentTypeId);
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
        // Delegate archetype construction to ArchetypeManager
        Archetype newArchetype = archetypeManager.getOrCreateArchetype(newMask);

        // Copy existing component data (only components present in both)
        ArchetypeChunk.ChunkLocation newLocation = newArchetype.addEntity(entityId);
        int[] componentIds = newMask.toComponentIdArray();
        for (int componentTypeId : componentIds) {
            if (oldRecord.mask.has(componentTypeId)) {
                int oldIdx = oldRecord.archetype.indexOfComponentType(componentTypeId);
                int newIdx = newArchetype.indexOfComponentType(componentTypeId);
                if (oldIdx >= 0 && newIdx >= 0) {
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

    // ============ Internal Records ============

    record EntityRecord(Archetype archetype, ArchetypeChunk.ChunkLocation location, ComponentMask mask) {}

    public record ComponentMetadata(int id, Class<?> type, long size) {}
}

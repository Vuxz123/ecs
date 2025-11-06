package com.ethnicthv.ecs.core.archetype;

import com.ethnicthv.ecs.core.api.archetype.IQueryBuilder;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.components.ManagedComponentStore;
import com.ethnicthv.ecs.core.components.SharedComponentStore;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ArchetypeWorld - Main ECS World using Archetype pattern
 * <p>
 * Manages entities, components, and archetypes for cache-friendly data access.
 * Entities with the same component composition are grouped together in archetypes.
 */
public final class ArchetypeWorld implements AutoCloseable {
    private final ComponentManager componentManager;
    private final ArchetypeManager archetypeManager;
    private final ConcurrentHashMap<Integer, EntityRecord> entityRecords; // entityId -> location in archetype
    private final ConcurrentHashMap<Class<?>, Integer> componentTypeIds;
    private final ConcurrentHashMap<Integer, ComponentMetadata> componentMetadata;
    private final Arena arena;
    private final AtomicInteger nextEntityId = new AtomicInteger(1);
    private final AtomicInteger nextComponentTypeId = new AtomicInteger(0);
    // New: global store for managed components
    private final ManagedComponentStore managedStore = new ManagedComponentStore();
    // New: store for managed shared components (de-duplicated)
    final SharedComponentStore sharedStore = new SharedComponentStore();

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
        ChunkGroup group = archetype.getOrCreateChunkGroup(new SharedValueKey(null, null));
        ArchetypeChunk.ChunkLocation location = group.addEntity(entityId);
        entityRecords.put(entityId, new EntityRecord(archetype, location, emptyMask, new SharedValueKey(null, null)));
        return entityId;
    }

    /**
     * Creates a new entity with the specified components.
     * The components will be initialized with default (zeroed) values.
     *
     * @param c1 The first component class to add.
     * @return The ID of the newly created entity.
     */
    public int createEntity(Class<?> c1) {
        return createEntityWithComponents(c1);
    }

    /**
     * Creates a new entity with the specified components.
     * @see #createEntity(Class)
     */
    public int createEntity(Class<?> c1, Class<?> c2) {
        return createEntityWithComponents(c1, c2);
    }

    /**
     * Creates a new entity with the specified components.
     * @see #createEntity(Class)
     */
    public int createEntity(Class<?> c1, Class<?> c2, Class<?> c3) {
        return createEntityWithComponents(c1, c2, c3);
    }

    /**
     * Creates a new entity with the specified components.
     * @see #createEntity(Class)
     */
    public int createEntity(Class<?> c1, Class<?> c2, Class<?> c3, Class<?> c4) {
        return createEntityWithComponents(c1, c2, c3, c4);
    }

    /**
     * Creates a new entity with the specified components.
     * @see #createEntity(Class)
     */
    public int createEntity(Class<?> c1, Class<?> c2, Class<?> c3, Class<?> c4, Class<?> c5) {
        return createEntityWithComponents(c1, c2, c3, c4, c5);
    }

    /**
     * Creates a new entity with the specified components.
     * @see #createEntity(Class)
     */
    public int createEntity(Class<?> c1, Class<?> c2, Class<?> c3, Class<?> c4, Class<?> c5, Class<?> c6) {
        return createEntityWithComponents(c1, c2, c3, c4, c5, c6);
    }

    private int createEntityWithComponents(Class<?>... componentClasses) {
        // 1. Create the entity ID
        int entityId = nextEntityId.getAndIncrement();

        // 2. Build the component mask
        ComponentMask mask = new ComponentMask();
        for (Class<?> componentClass : componentClasses) {
            Integer componentTypeId = componentTypeIds.get(componentClass);
            if (componentTypeId == null) {
                throw new IllegalArgumentException("Component type " + componentClass.getName() + " is not registered.");
            }
            mask = mask.set(componentTypeId);
        }

        // 3. Get or create the target archetype
        Archetype archetype = archetypeManager.getOrCreateArchetype(mask);
        ChunkGroup group = archetype.getOrCreateChunkGroup(new SharedValueKey(null, null));

        // 4. Add the entity to the archetype and store its record
        ArchetypeChunk.ChunkLocation location = group.addEntity(entityId);
        entityRecords.put(entityId, new EntityRecord(archetype, location, mask, new SharedValueKey(null, null)));

        // 5. Allocate and assign default (zeroed) memory for each unmanaged component
        for (Class<?> componentClass : componentClasses) {
            Integer componentTypeId = getComponentTypeId(componentClass);
            if (componentTypeId != null) {
                ComponentDescriptor desc = componentManager.getDescriptor(componentClass);
                if (desc != null && !desc.isManaged()) {
                    MemorySegment data = componentManager.allocate(componentClass, arena); // Zeroed by default
                    int componentIndex = archetype.indexOfComponentType(componentTypeId);
                    if (componentIndex >= 0) {
                        archetype.setComponentData(location, componentIndex, data);
                    }
                }
                // For managed components, default ticket remains -1 until user provides an instance
            }
        }

        return entityId;
    }

    /**
     * Add a component to an entity (unmanaged path: memory segment).
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
        ComponentDescriptor desc = componentManager.getDescriptor(componentClass);
        if (desc != null && desc.isManaged()) {
            throw new IllegalArgumentException("addComponent(entityId, Class, MemorySegment) not valid for managed component " + componentClass.getName());
        }

        // Create new mask with the additional component
        ComponentMask newMask = record.mask.set(componentTypeId);

        // Move entity to new archetype (preserve shared key)
        moveEntityToArchetype(entityId, record, record.mask, newMask, record.sharedKey);

        // Set component data
        EntityRecord newRecord = entityRecords.get(entityId);
        int componentIndex = newRecord.archetype.indexOfComponentType(componentTypeId);
        newRecord.archetype.setComponentData(newRecord.location, componentIndex, data);
    }

    /**
     * Add a managed component instance to an entity. The instance is stored in the ManagedComponentStore
     * and the ticket is placed into the chunk's managed index array.
     */
    public <T> void addComponent(int entityId, T componentInstance) {
        if (componentInstance == null) throw new IllegalArgumentException("componentInstance must not be null");
        Class<?> componentClass = componentInstance.getClass();

        EntityRecord record = entityRecords.get(entityId);
        if (record == null) {
            throw new IllegalArgumentException("Entity " + entityId + " does not exist");
        }

        Integer componentTypeId = componentTypeIds.get(componentClass);
        if (componentTypeId == null) {
            throw new IllegalArgumentException("Component type " + componentClass + " not registered");
        }

        ComponentDescriptor desc = componentManager.getDescriptor(componentClass);
        if (desc == null || !desc.isManaged()) {
            throw new IllegalArgumentException("Component " + componentClass.getName() + " is not marked @Component.Managed");
        }

        // 1) Store managed instance and get ticket
        int ticket = managedStore.store(componentInstance);

        // 2) Structural change: move entity to new archetype that includes this component
        ComponentMask newMask = record.mask.set(componentTypeId);
        moveEntityToArchetype(entityId, record, record.mask, newMask, record.sharedKey);

        // 3) Wire ticket into the new chunk's managed index array
        EntityRecord newRecord = entityRecords.get(entityId);
        int managedTypeIndex = newRecord.archetype.getManagedTypeIndex(componentTypeId);
        if (managedTypeIndex < 0) {
            // Should not happen if descriptor is managed and mask includes it
            throw new IllegalStateException("Managed type index not found for component id=" + componentTypeId);
        }
        ArchetypeChunk chunk = newRecord.archetype.getChunk(newRecord.location.chunkIndex);
        chunk.setManagedTicket(managedTypeIndex, newRecord.location.indexInChunk, ticket);
    }

    /**
     * Remove a component from an entity. For managed components, release the stored object.
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

        // If managed, release the ticket before moving
        ComponentDescriptor desc = componentManager.getDescriptor(componentClass);
        if (desc != null && desc.isManaged()) {
            int managedIdx = record.archetype.getManagedTypeIndex(componentTypeId);
            if (managedIdx >= 0) {
                ArchetypeChunk chunk = record.archetype.getChunk(record.location.chunkIndex);
                int ticket = chunk.getManagedTicket(managedIdx, record.location.indexInChunk);
                if (ticket >= 0) {
                    managedStore.release(ticket);
                }
            }
        }

        // Create new mask without the component
        ComponentMask newMask = record.mask.clear(componentTypeId);

        // Move entity to new archetype, preserve shared key
        moveEntityToArchetype(entityId, record, record.mask, newMask, record.sharedKey);
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
            // Release all managed tickets for this entity before removing
            int[] managedIds = record.archetype.getManagedTypeIds();
            if (managedIds != null && managedIds.length > 0) {
                ArchetypeChunk chunk = record.archetype.getChunk(record.location.chunkIndex);
                for (int i = 0; i < managedIds.length; i++) {
                    int ticket = chunk.getManagedTicket(i, record.location.indexInChunk);
                    if (ticket >= 0) managedStore.release(ticket);
                }
            }
            // Release shared managed tickets referenced by key
            if (record.sharedKey != null && record.sharedKey.managedSharedIndices() != null) {
                for (int idx : record.sharedKey.managedSharedIndices()) {
                    if (idx >= 0) sharedStore.releaseSharedIndex(idx);
                }
            }
            // Remove from the correct chunk group
            ChunkGroup group = record.archetype.getChunkGroup(record.sharedKey);
            if (group != null) group.removeEntity(record.location);
        }
    }

    /**
     * Create a query builder for entities matching component requirements.
     * <p>
     * Returns a builder that can be configured with component requirements,
     * then built into an immutable {@link com.ethnicthv.ecs.core.api.archetype.IQuery}.
     *
     * @return a new query builder
     */
    public IQueryBuilder query() {
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

    /**
     * Retrieve a managed component instance for an entity, or null if absent.
     */
    @SuppressWarnings("unchecked")
    public <T> T getManagedComponent(int entityId, Class<T> componentClass) {
        EntityRecord record = entityRecords.get(entityId);
        if (record == null) return null;
        Integer typeId = componentTypeIds.get(componentClass);
        if (typeId == null || !record.mask.has(typeId)) return null;
        ComponentDescriptor desc = componentManager.getDescriptor(componentClass);
        if (desc == null || !desc.isManaged()) return null;
        int mIdx = record.archetype.getManagedTypeIndex(typeId);
        if (mIdx < 0) return null;
        ArchetypeChunk chunk = record.archetype.getChunk(record.location.chunkIndex);
        int ticket = chunk.getManagedTicket(mIdx, record.location.indexInChunk);
        if (ticket < 0) return null;
        Object obj = managedStore.get(ticket);
        return (T) obj;
    }

    /**
     * Replace or set a managed component instance for an entity.
     * If an old instance exists, its ticket is released; the new instance is stored and wired.
     */
    public <T> void setManagedComponent(int entityId, T newInstance) {
        if (newInstance == null) throw new IllegalArgumentException("newInstance must not be null");
        Class<?> componentClass = newInstance.getClass();
        Integer typeId = componentTypeIds.get(componentClass);
        if (typeId == null) throw new IllegalArgumentException("Component type not registered: " + componentClass.getName());
        ComponentDescriptor desc = componentManager.getDescriptor(componentClass);
        if (desc == null || !desc.isManaged()) {
            throw new IllegalArgumentException("Component is not managed: " + componentClass.getName());
        }
        EntityRecord record = entityRecords.get(entityId);
        if (record == null || !record.mask.has(typeId)) {
            // If not present, this behaves like addComponent(entityId, instance)
            addComponent(entityId, newInstance);
            return;
        }
        // Release old ticket (if any)
        int mIdx = record.archetype.getManagedTypeIndex(typeId);
        ArchetypeChunk chunk = record.archetype.getChunk(record.location.chunkIndex);
        int oldTicket = chunk.getManagedTicket(mIdx, record.location.indexInChunk);
        if (oldTicket >= 0) managedStore.release(oldTicket);
        // Store new instance and set ticket
        int newTicket = managedStore.store(newInstance);
        chunk.setManagedTicket(mIdx, record.location.indexInChunk, newTicket);
    }

    // ============ Shared Component APIs ============

    public <T> void setSharedComponent(int entityId, T managedValue) {
        if (managedValue == null) throw new IllegalArgumentException("managedValue must not be null");
        Class<?> type = managedValue.getClass();
        Integer typeId = componentTypeIds.get(type);
        if (typeId == null) throw new IllegalArgumentException("Shared component type not registered: " + type.getName());
        ComponentDescriptor desc = componentManager.getDescriptor(type);
        if (desc == null || desc.getKind() != ComponentDescriptor.ComponentKind.SHARED_MANAGED) {
            throw new IllegalArgumentException("Type is not a @SharedComponent managed type: " + type.getName());
        }
        EntityRecord record = entityRecords.get(entityId);
        if (record == null) throw new IllegalArgumentException("Entity does not exist: " + entityId);
        int ticket = sharedStore.getOrAddSharedIndex(managedValue);
        SharedValueKey oldKey = record.sharedKey;
        int[] managedIdx;
        if (oldKey != null && oldKey.managedSharedIndices() != null) {
            managedIdx = oldKey.managedSharedIndices().clone();
        } else {
            managedIdx = new int[record.archetype.getSharedManagedTypeIds().length];
            Arrays.fill(managedIdx, -1);
        }
        int pos = record.archetype.getSharedManagedIndex(typeId);
        if (pos < 0) throw new IllegalArgumentException("Shared managed type not part of this archetype: id=" + typeId);
        int old = managedIdx[pos];
        managedIdx[pos] = ticket;
        SharedValueKey newKey = new SharedValueKey(managedIdx, oldKey != null ? oldKey.unmanagedSharedValues() : null);
        if (oldKey != null && Arrays.equals(oldKey.managedSharedIndices(), managedIdx)) return;
        moveEntityToArchetype(entityId, record, record.mask, record.mask, newKey);
        if (oldKey != null && old >= 0 && old != ticket) sharedStore.releaseSharedIndex(old);
    }

    public void setSharedComponent(int entityId, Class<?> unmanagedSharedType, long value) {
        Integer typeId = componentTypeIds.get(unmanagedSharedType);
        if (typeId == null) throw new IllegalArgumentException("Shared component type not registered: " + unmanagedSharedType.getName());
        ComponentDescriptor desc = componentManager.getDescriptor(unmanagedSharedType);
        if (desc == null || desc.getKind() != ComponentDescriptor.ComponentKind.SHARED_UNMANAGED) {
            throw new IllegalArgumentException("Type is not an @UnmanagedSharedComponent type: " + unmanagedSharedType.getName());
        }
        EntityRecord record = entityRecords.get(entityId);
        if (record == null) throw new IllegalArgumentException("Entity does not exist: " + entityId);
        SharedValueKey oldKey = record.sharedKey;
        long[] unmanagedVals;
        if (oldKey != null && oldKey.unmanagedSharedValues() != null) {
            unmanagedVals = oldKey.unmanagedSharedValues().clone();
        } else {
            unmanagedVals = new long[record.archetype.getSharedUnmanagedTypeIds().length];
            Arrays.fill(unmanagedVals, Long.MIN_VALUE);
        }
        int pos = record.archetype.getSharedUnmanagedIndex(typeId);
        if (pos < 0) throw new IllegalArgumentException("Shared unmanaged type not part of this archetype: id=" + typeId);
        unmanagedVals[pos] = value;
        SharedValueKey newKey = new SharedValueKey(oldKey != null ? oldKey.managedSharedIndices() : null, unmanagedVals);
        if (oldKey != null && Arrays.equals(oldKey.unmanagedSharedValues(), unmanagedVals)) return;
        moveEntityToArchetype(entityId, record, record.mask, record.mask, newKey);
    }

    // Expose non-mutating shared index lookup for query building
    public int findSharedIndex(Object value) {
        return sharedStore.findIndex(value);
    }

    // ============ Internal Methods ============

    private void moveEntityToArchetype(int entityId, EntityRecord oldRecord, ComponentMask oldMask, ComponentMask newMask, SharedValueKey newSharedKey) {
        // Delegate archetype construction to ArchetypeManager
        Archetype newArchetype = archetypeManager.getOrCreateArchetype(newMask);
        ChunkGroup newGroup = newArchetype.getOrCreateChunkGroup(newSharedKey);
        ArchetypeChunk.ChunkLocation newLocation = newGroup.addEntity(entityId);

        // Copy unmanaged instance data intersection
        int[] typeIds = newMask.toComponentIdArray();
        for (int componentTypeId : typeIds) {
            if (oldMask.has(componentTypeId)) {
                int oldIdx = oldRecord.archetype.indexOfComponentType(componentTypeId);
                int newIdx = newArchetype.indexOfComponentType(componentTypeId);
                if (oldIdx >= 0 && newIdx >= 0) {
                    MemorySegment oldData = oldRecord.archetype.getComponentData(oldRecord.location, oldIdx);
                    if (oldData != null) newArchetype.setComponentData(newLocation, newIdx, oldData);
                }
            }
        }

        // Transfer managed instance tickets intersection
        int[] oldManaged = oldRecord.archetype.getManagedTypeIds();
        int[] newManaged = newArchetype.getManagedTypeIds();
        if (oldManaged != null && newManaged != null && oldManaged.length > 0 && newManaged.length > 0) {
            ArchetypeChunk oldChunk = oldRecord.archetype.getChunk(oldRecord.location.chunkIndex);
            ArchetypeChunk newChunk = newArchetype.getChunk(newLocation.chunkIndex);
            for (int tid : oldManaged) {
                int oldMIdx = oldRecord.archetype.getManagedTypeIndex(tid);
                int newMIdx = newArchetype.getManagedTypeIndex(tid);
                if (oldMIdx >= 0 && newMIdx >= 0) {
                    int ticket = oldChunk.getManagedTicket(oldMIdx, oldRecord.location.indexInChunk);
                    if (ticket >= 0) newChunk.setManagedTicket(newMIdx, newLocation.indexInChunk, ticket);
                }
            }
        }

        // Remove from old group and update
        ChunkGroup oldGroup = oldRecord.archetype.getChunkGroup(oldRecord.sharedKey);
        if (oldGroup != null) oldGroup.removeEntity(oldRecord.location);
        entityRecords.put(entityId, new EntityRecord(newArchetype, newLocation, newMask, newSharedKey));
    }

    // ============ Internal Records ============

    record EntityRecord(Archetype archetype, ArchetypeChunk.ChunkLocation location, ComponentMask mask, SharedValueKey sharedKey) {}

    public record ComponentMetadata(int id, Class<?> type, long size) {}
}

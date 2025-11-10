package com.ethnicthv.ecs.core.archetype;

import com.ethnicthv.ecs.core.api.archetype.IQueryBuilder;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.components.ManagedComponentStore;
import com.ethnicthv.ecs.core.components.SharedComponentStore;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            throw new IllegalStateException("Managed type index not found for component id=" + componentTypeId);
        }
        ChunkGroup newGroup = newRecord.archetype.getChunkGroup(newRecord.sharedKey);
        if (newGroup == null) throw new IllegalStateException("ChunkGroup not found for new entity shared key");
        ArchetypeChunk newChunk = newGroup.getChunk(newRecord.location.chunkIndex);
        newChunk.setManagedTicket(managedTypeIndex, newRecord.location.indexInChunk, ticket);
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
                ChunkGroup group = record.archetype.getChunkGroup(record.sharedKey);
                if (group != null) {
                    ArchetypeChunk chunk = group.getChunk(record.location.chunkIndex);
                    int ticket = chunk.getManagedTicket(managedIdx, record.location.indexInChunk);
                    if (ticket >= 0) {
                        managedStore.release(ticket);
                    }
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
                ChunkGroup group = record.archetype.getChunkGroup(record.sharedKey);
                if (group != null) {
                    ArchetypeChunk chunk = group.getChunk(record.location.chunkIndex);
                    for (int i = 0; i < managedIds.length; i++) {
                        int ticket = chunk.getManagedTicket(i, record.location.indexInChunk);
                        if (ticket >= 0) managedStore.release(ticket);
                    }
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
        ChunkGroup group = record.archetype.getChunkGroup(record.sharedKey);
        if (group == null) return null;
        ArchetypeChunk chunk = group.getChunk(record.location.chunkIndex);
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
        ChunkGroup group = record.archetype.getChunkGroup(record.sharedKey);
        if (group == null) throw new IllegalStateException("ChunkGroup not found for entity shared key");
        ArchetypeChunk chunk = group.getChunk(record.location.chunkIndex);
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
            throw new IllegalArgumentException("Type is not a @Shared @Managed Component type: " + type.getName());
        }
        EntityRecord record = entityRecords.get(entityId);
        if (record == null) throw new IllegalArgumentException("Entity does not exist: " + entityId);
        // If current archetype does not yet contain this shared type, structurally add it by extending the mask.
        int pos = record.archetype.getSharedManagedIndex(typeId);
        if (pos < 0) {
            ComponentMask newMask = record.mask.set(typeId);
            // Move entity to new archetype with extended mask (retain old shared key first)
            moveEntityToArchetype(entityId, record, record.mask, newMask, record.sharedKey);
            record = entityRecords.get(entityId);
            pos = record.archetype.getSharedManagedIndex(typeId);
            if (pos < 0) throw new IllegalStateException("Shared managed type index still missing after structural add: id=" + typeId);
        }
        int ticket = sharedStore.getOrAddSharedIndex(managedValue);
        SharedValueKey oldKey = record.sharedKey;
        int[] managedIdx;
        // Prepare sized array matching current archetype's shared managed type ids
        int managedCount = record.archetype.getSharedManagedTypeIds().length;
        if (oldKey != null && oldKey.managedSharedIndices() != null && oldKey.managedSharedIndices().length == managedCount) {
            managedIdx = oldKey.managedSharedIndices().clone();
        } else {
            managedIdx = new int[managedCount];
            java.util.Arrays.fill(managedIdx, -1);
            if (oldKey != null && oldKey.managedSharedIndices() != null) {
                // Copy overlapping old indices into new sized array (in case of expansion)
                int copyLen = Math.min(managedIdx.length, oldKey.managedSharedIndices().length);
                System.arraycopy(oldKey.managedSharedIndices(), 0, managedIdx, 0, copyLen);
            }
        }
        int old = managedIdx[pos];
        managedIdx[pos] = ticket;
        SharedValueKey newKey = new SharedValueKey(managedIdx, oldKey != null ? oldKey.unmanagedSharedValues() : null);
        if (oldKey != null && java.util.Arrays.equals(oldKey.managedSharedIndices(), managedIdx)) return; // unchanged
        moveEntityToArchetype(entityId, record, record.mask, record.mask, newKey);
        if (oldKey != null && old >= 0 && old != ticket) sharedStore.releaseSharedIndex(old);
    }

    public void setSharedComponent(int entityId, Class<?> unmanagedSharedType, long value) {
        Integer typeId = componentTypeIds.get(unmanagedSharedType);
        if (typeId == null) throw new IllegalArgumentException("Shared component type not registered: " + unmanagedSharedType.getName());
        ComponentDescriptor desc = componentManager.getDescriptor(unmanagedSharedType);
        if (desc == null || desc.getKind() != ComponentDescriptor.ComponentKind.SHARED_UNMANAGED) {
            throw new IllegalArgumentException("Type is not an @Unmanaged @Shared Component type: " + unmanagedSharedType.getName());
        }
        EntityRecord record = entityRecords.get(entityId);
        if (record == null) throw new IllegalArgumentException("Entity does not exist: " + entityId);
        // Ensure archetype contains shared unmanaged type; if not, extend mask.
        int pos = record.archetype.getSharedUnmanagedIndex(typeId);
        if (pos < 0) {
            ComponentMask newMask = record.mask.set(typeId);
            moveEntityToArchetype(entityId, record, record.mask, newMask, record.sharedKey);
            record = entityRecords.get(entityId);
            pos = record.archetype.getSharedUnmanagedIndex(typeId);
            if (pos < 0) throw new IllegalStateException("Shared unmanaged type index still missing after structural add: id=" + typeId);
        }
        SharedValueKey oldKey = record.sharedKey;
        int unmanagedCount = record.archetype.getSharedUnmanagedTypeIds().length;
        long[] unmanagedVals;
        if (oldKey != null && oldKey.unmanagedSharedValues() != null && oldKey.unmanagedSharedValues().length == unmanagedCount) {
            unmanagedVals = oldKey.unmanagedSharedValues().clone();
        } else {
            unmanagedVals = new long[unmanagedCount];
            java.util.Arrays.fill(unmanagedVals, Long.MIN_VALUE);
            if (oldKey != null && oldKey.unmanagedSharedValues() != null) {
                int copyLen = Math.min(unmanagedVals.length, oldKey.unmanagedSharedValues().length);
                System.arraycopy(oldKey.unmanagedSharedValues(), 0, unmanagedVals, 0, copyLen);
            }
        }
        long oldVal = unmanagedVals[pos];
        unmanagedVals[pos] = value;
        SharedValueKey newKey = new SharedValueKey(oldKey != null ? oldKey.managedSharedIndices() : null, unmanagedVals);
        if (oldKey != null && java.util.Arrays.equals(oldKey.unmanagedSharedValues(), unmanagedVals)) return; // unchanged
        moveEntityToArchetype(entityId, record, record.mask, record.mask, newKey);
        // no ticket release for unmanaged values
    }

    // Expose non-mutating shared index lookup for query building
    public int findSharedIndex(Object value) {
        return sharedStore.findIndex(value);
    }

    // ============ Batch API ============
    public static final class EntityBatch {
        public final int[] entityIds;
        public final int size;
        private EntityBatch(int[] ids) { this.entityIds = ids; this.size = ids.length; }
        public static EntityBatch of(int... ids) { return new EntityBatch(Arrays.copyOf(ids, ids.length)); }
    }

    public void setSharedComponent(EntityBatch batch, Object sharedValue) {
        if (batch == null || batch.size == 0) return;
        Objects.requireNonNull(sharedValue, "sharedValue");
        Class<?> type = sharedValue.getClass();
        Integer typeId = componentTypeIds.get(type);
        if (typeId == null) throw new IllegalArgumentException("Shared component type not registered: " + type.getName());
        ComponentDescriptor desc = componentManager.getDescriptor(type);
        if (desc == null || desc.getKind() != ComponentDescriptor.ComponentKind.SHARED_MANAGED) {
            throw new IllegalArgumentException("Type is not a @Shared @Managed Component type: " + type.getName());
        }
        int ticket = sharedStore.getOrAddSharedIndex(sharedValue);
        // Group by old group
        Map<ChunkGroup, List<Integer>> byOldGroup = new HashMap<>();
        for (int eid : batch.entityIds) {
            EntityRecord rec = entityRecords.get(eid);
            if (rec == null) continue;
            ChunkGroup oldGroup = rec.archetype.getChunkGroup(rec.sharedKey);
            if (oldGroup == null) continue;
            byOldGroup.computeIfAbsent(oldGroup, k -> new ArrayList<>()).add(eid);
        }
        for (Map.Entry<ChunkGroup, List<Integer>> entry : byOldGroup.entrySet()) {
            List<Integer> eids = entry.getValue();
            if (eids.isEmpty()) continue;
            ChunkGroup oldGroup = entry.getKey();
            EntityRecord first = entityRecords.get(eids.get(0));
            Archetype archetype = first.archetype;
            int pos = archetype.getSharedManagedIndex(typeId);
            // If archetype doesn't contain the shared type, fallback per-entity (mask extend) to keep logic simple now
            if (pos < 0) {
                for (int eid : eids) setSharedComponent(eid, sharedValue);
                continue;
            }
            // Build eids needing change and compute their respective newKey
            Map<SharedValueKey, List<Integer>> byTargetKey = new HashMap<>();
            for (int eid : eids) {
                EntityRecord rec = entityRecords.get(eid);
                if (rec == null) continue;
                SharedValueKey oldKey = rec.sharedKey;
                int managedCount = archetype.getSharedManagedTypeIds().length;
                int[] managedIdx;
                long[] unmanagedVals;
                if (oldKey != null && oldKey.managedSharedIndices() != null && oldKey.managedSharedIndices().length == managedCount) {
                    managedIdx = oldKey.managedSharedIndices().clone();
                } else {
                    managedIdx = new int[managedCount];
                    java.util.Arrays.fill(managedIdx, -1);
                    if (oldKey != null && oldKey.managedSharedIndices() != null) {
                        int copyLen = Math.min(managedIdx.length, oldKey.managedSharedIndices().length);
                        System.arraycopy(oldKey.managedSharedIndices(), 0, managedIdx, 0, copyLen);
                    }
                }
                unmanagedVals = (oldKey != null ? oldKey.unmanagedSharedValues() : null);
                int prev = managedIdx[pos];
                if (prev == ticket) continue; // unchanged
                managedIdx[pos] = ticket;
                SharedValueKey newKey = new SharedValueKey(managedIdx, unmanagedVals);
                byTargetKey.computeIfAbsent(newKey, k -> new ArrayList<>()).add(eid);
            }
            for (Map.Entry<SharedValueKey, List<Integer>> g : byTargetKey.entrySet()) {
                SharedValueKey newKey = g.getKey();
                ChunkGroup newGroup = archetype.getOrCreateChunkGroup(newKey);
                if (newGroup == oldGroup) continue;
                batchMoveShared(archetype, oldGroup, newGroup, g.getValue(), first.mask, first.mask, newKey);
            }
        }
    }

    // ============ Internal Methods ============

    private void moveEntityToArchetype(int entityId, EntityRecord oldRecord, ComponentMask oldMask, ComponentMask newMask, SharedValueKey newSharedKey) {
        Archetype newArchetype = archetypeManager.getOrCreateArchetype(newMask);
        ChunkGroup newGroup = newArchetype.getOrCreateChunkGroup(newSharedKey);
        // Determine old group from current record
        ChunkGroup oldGroup = oldRecord.archetype.getChunkGroup(oldRecord.sharedKey);

        // Acquire locks in stable order to avoid deadlocks; handle null oldGroup
        if (oldGroup == null || oldGroup == newGroup) {
            newGroup.getResizeLock().lock();
            try {
                // Allocate in target
                ArchetypeChunk.ChunkLocation newLocation = newGroup.addEntity(entityId);
                ArchetypeChunk newChunk = newGroup.getChunk(newLocation.chunkIndex);
                // Copy unmanaged data intersection (oldGroup may be null; skip copies if so)
                ArchetypeChunk oldChunk = null;
                if (oldGroup != null) {
                    oldChunk = oldGroup.getChunk(oldRecord.location.chunkIndex);
                }
                int[] typeIds = newMask.toComponentIdArray();
                if (oldChunk != null) {
                    for (int componentTypeId : typeIds) {
                        if (oldMask.has(componentTypeId)) {
                            int oldIdx = oldRecord.archetype.indexOfComponentType(componentTypeId);
                            int newIdx = newArchetype.indexOfComponentType(componentTypeId);
                            if (oldIdx >= 0 && newIdx >= 0) {
                                var oldData = oldChunk.getComponentData(oldIdx, oldRecord.location.indexInChunk);
                                if (oldData != null) newChunk.setComponentData(newIdx, newLocation.indexInChunk, oldData);
                            }
                        }
                    }
                    // Transfer managed tickets intersection
                    int[] oldManaged = oldRecord.archetype.getManagedTypeIds();
                    int[] newManaged = newArchetype.getManagedTypeIds();
                    if (oldManaged != null && newManaged != null && oldManaged.length > 0 && newManaged.length > 0) {
                        for (int tid : oldManaged) {
                            int oldMIdx = oldRecord.archetype.getManagedTypeIndex(tid);
                            int newMIdx = newArchetype.getManagedTypeIndex(tid);
                            if (oldMIdx >= 0 && newMIdx >= 0) {
                                int ticket = oldChunk.getManagedTicket(oldMIdx, oldRecord.location.indexInChunk);
                                if (ticket >= 0) newChunk.setManagedTicket(newMIdx, newLocation.indexInChunk, ticket);
                            }
                        }
                    }
                    // Remove from old group now that copy is done
                    oldGroup.removeEntity(oldRecord.location);
                }
                // Update record
                entityRecords.put(entityId, new EntityRecord(newArchetype, newLocation, newMask, newSharedKey));
            } finally {
                newGroup.getResizeLock().unlock();
            }
            return;
        }

        // Two distinct groups: lock both in identity order
        ChunkGroup first = oldGroup;
        ChunkGroup second = newGroup;
        if (System.identityHashCode(first) > System.identityHashCode(second)) { first = newGroup; second = oldGroup; }
        first.getResizeLock().lock();
        try {
            second.getResizeLock().lock();
            try {
                // Allocate in target
                ArchetypeChunk.ChunkLocation newLocation = newGroup.addEntity(entityId);
                ArchetypeChunk oldChunk = oldGroup.getChunk(oldRecord.location.chunkIndex);
                ArchetypeChunk newChunk = newGroup.getChunk(newLocation.chunkIndex);
                // Copy unmanaged instance data intersection
                int[] typeIds = newMask.toComponentIdArray();
                for (int componentTypeId : typeIds) {
                    if (oldMask.has(componentTypeId)) {
                        int oldIdx = oldRecord.archetype.indexOfComponentType(componentTypeId);
                        int newIdx = newArchetype.indexOfComponentType(componentTypeId);
                        if (oldIdx >= 0 && newIdx >= 0) {
                            var oldData = oldChunk.getComponentData(oldIdx, oldRecord.location.indexInChunk);
                            if (oldData != null) newChunk.setComponentData(newIdx, newLocation.indexInChunk, oldData);
                        }
                    }
                }
                // Transfer managed instance tickets intersection
                int[] oldManaged = oldRecord.archetype.getManagedTypeIds();
                int[] newManaged = newArchetype.getManagedTypeIds();
                if (oldManaged != null && newManaged != null && oldManaged.length > 0 && newManaged.length > 0) {
                    for (int tid : oldManaged) {
                        int oldMIdx = oldRecord.archetype.getManagedTypeIndex(tid);
                        int newMIdx = newArchetype.getManagedTypeIndex(tid);
                        if (oldMIdx >= 0 && newMIdx >= 0) {
                            int ticket = oldChunk.getManagedTicket(oldMIdx, oldRecord.location.indexInChunk);
                            if (ticket >= 0) newChunk.setManagedTicket(newMIdx, newLocation.indexInChunk, ticket);
                        }
                    }
                }
                // Remove from old group and update record
                oldGroup.removeEntity(oldRecord.location);
                entityRecords.put(entityId, new EntityRecord(newArchetype, newLocation, newMask, newSharedKey));
            } finally {
                second.getResizeLock().unlock();
            }
        } finally {
            first.getResizeLock().unlock();
        }
    }

    private void batchMoveShared(Archetype archetype, ChunkGroup oldGroup, ChunkGroup newGroup, List<Integer> eids, ComponentMask oldMask, ComponentMask newMask, SharedValueKey newSharedKey) {
        if (eids == null || eids.isEmpty()) return;
        ChunkGroup first = oldGroup;
        ChunkGroup second = newGroup;
        if (System.identityHashCode(first) > System.identityHashCode(second)) { first = newGroup; second = oldGroup; }
        first.getResizeLock().lock();
        try {
            if (second != first) second.getResizeLock().lock();
            try {
                int n = eids.size();
                int[] idsArr = new int[n];
                for (int i = 0; i < n; i++) idsArr[i] = eids.get(i);
                ArchetypeChunk.ChunkLocation[] newLocs = newGroup.addEntities(idsArr);
                ArchetypeChunk.ChunkLocation[] oldLocs = new ArchetypeChunk.ChunkLocation[n];
                for (int i = 0; i < n; i++) oldLocs[i] = entityRecords.get(idsArr[i]).location;

                // Precompute unmanaged intersection index pairs
                int[] compTypeIds = archetype.getComponentTypeIds();
                int maxC = compTypeIds != null ? compTypeIds.length : 0;
                int[] compOldIdx = new int[maxC];
                int[] compNewIdx = new int[maxC];
                int cCount = 0;
                for (int t = 0; t < maxC; t++) {
                    int compTypeId = compTypeIds[t];
                    if (!oldMask.has(compTypeId) || !newMask.has(compTypeId)) continue;
                    int oi = archetype.indexOfComponentType(compTypeId);
                    int ni = oi; // same archetype
                    if (oi >= 0 && ni >= 0) { compOldIdx[cCount] = oi; compNewIdx[cCount] = ni; cCount++; }
                }
                // Precompute managed intersection pairs
                int[] managedTypeIds = archetype.getManagedTypeIds();
                int maxM = managedTypeIds != null ? managedTypeIds.length : 0;
                int[] manIdx = new int[maxM]; // same index on both sides for same archetype
                int mCount = 0;
                for (int t = 0; t < maxM; t++) {
                    int tid = managedTypeIds[t];
                    if (!oldMask.has(tid) || !newMask.has(tid)) continue;
                    int mi = archetype.getManagedTypeIndex(tid);
                    if (mi >= 0) { manIdx[mCount] = mi; mCount++; }
                }

                // Entity-first copy using precomputed indices
                for (int i = 0; i < n; i++) {
                    ArchetypeChunk oldChunk = oldGroup.getChunk(oldLocs[i].chunkIndex);
                    ArchetypeChunk newChunk = newGroup.getChunk(newLocs[i].chunkIndex);
                    int oldSlot = oldLocs[i].indexInChunk;
                    int newSlot = newLocs[i].indexInChunk;
                    for (int k = 0; k < cCount; k++) {
                        MemorySegment src = oldChunk.getComponentData(compOldIdx[k], oldSlot);
                        newChunk.setComponentData(compNewIdx[k], newSlot, src);
                    }
                    for (int k = 0; k < mCount; k++) {
                        int mi = manIdx[k];
                        int ticket = oldChunk.getManagedTicket(mi, oldSlot);
                        if (ticket >= 0) newChunk.setManagedTicket(mi, newSlot, ticket);
                    }
                }

                oldGroup.removeEntities(oldLocs);
                for (int i = 0; i < n; i++) entityRecords.put(idsArr[i], new EntityRecord(archetype, newLocs[i], newMask, newSharedKey));
            } finally {
                if (second != first) second.getResizeLock().unlock();
            }
        } finally {
            first.getResizeLock().unlock();
        }
    }

    private void batchMoveStructural(Archetype oldArch, Archetype newArch, ChunkGroup oldGroup, ChunkGroup newGroup, List<Integer> eids, ComponentMask oldMask, ComponentMask newMask, SharedValueKey key) {
        if (eids.isEmpty()) return;
        ChunkGroup first = oldGroup;
        ChunkGroup second = newGroup;
        if (System.identityHashCode(first) > System.identityHashCode(second)) { first = newGroup; second = oldGroup; }
        first.getResizeLock().lock();
        try {
            if (second != first) second.getResizeLock().lock();
            try {
                int n = eids.size();
                int[] ids = new int[n];
                for (int i = 0; i < n; i++) ids[i] = eids.get(i);
                ArchetypeChunk.ChunkLocation[] newLocs = newGroup.addEntities(ids);
                ArchetypeChunk.ChunkLocation[] oldLocs = new ArchetypeChunk.ChunkLocation[n];
                for (int i = 0; i < n; i++) oldLocs[i] = entityRecords.get(ids[i]).location;

                // Precompute unmanaged intersection index pairs between oldArch and newArch
                int[] compTypeIds = newArch.getComponentTypeIds();
                int maxC = compTypeIds != null ? compTypeIds.length : 0;
                int[] compOldIdx = new int[maxC];
                int[] compNewIdx = new int[maxC];
                int cCount = 0;
                for (int t = 0; t < maxC; t++) {
                    int compTypeId = compTypeIds[t];
                    if (!oldMask.has(compTypeId) || !newMask.has(compTypeId)) continue;
                    int oi = oldArch.indexOfComponentType(compTypeId);
                    int ni = newArch.indexOfComponentType(compTypeId);
                    if (oi >= 0 && ni >= 0) { compOldIdx[cCount] = oi; compNewIdx[cCount] = ni; cCount++; }
                }
                // Precompute managed intersection and removals
                int[] oldManagedIds = oldArch.getManagedTypeIds();
                int[] newManagedIds = newArch.getManagedTypeIds();
                int maxMi = oldManagedIds != null ? oldManagedIds.length : 0;
                int[] manOldIdx = new int[maxMi];
                int[] manNewIdx = new int[maxMi];
                int miCount = 0;
                int[] removeOldIdx = new int[maxMi];
                int rCount = 0;
                if (oldManagedIds != null) {
                    for (int tid : oldManagedIds) {
                        int oi = oldArch.getManagedTypeIndex(tid);
                        boolean inOld = oldMask.has(tid);
                        boolean inNew = newMask.has(tid);
                        if (inOld && inNew) {
                            int ni = newArch.getManagedTypeIndex(tid);
                            if (oi >= 0 && ni >= 0) { manOldIdx[miCount] = oi; manNewIdx[miCount] = ni; miCount++; }
                        } else if (inOld && !inNew) {
                            if (oi >= 0) { removeOldIdx[rCount] = oi; rCount++; }
                        }
                    }
                }

                // Entity-first copy and releases using precomputed indices
                for (int i = 0; i < n; i++) {
                    ArchetypeChunk oldChunk = oldGroup.getChunk(oldLocs[i].chunkIndex);
                    ArchetypeChunk newChunk = newGroup.getChunk(newLocs[i].chunkIndex);
                    int oldSlot = oldLocs[i].indexInChunk;
                    int newSlot = newLocs[i].indexInChunk;
                    for (int k = 0; k < cCount; k++) {
                        MemorySegment src = oldChunk.getComponentData(compOldIdx[k], oldSlot);
                        newChunk.setComponentData(compNewIdx[k], newSlot, src);
                    }
                    for (int k = 0; k < miCount; k++) {
                        int ticket = oldChunk.getManagedTicket(manOldIdx[k], oldSlot);
                        if (ticket >= 0) newChunk.setManagedTicket(manNewIdx[k], newSlot, ticket);
                    }
                    for (int k = 0; k < rCount; k++) {
                        int ticket = oldChunk.getManagedTicket(removeOldIdx[k], oldSlot);
                        if (ticket >= 0) managedStore.release(ticket);
                    }
                }

                oldGroup.removeEntities(oldLocs);
                for (int i = 0; i < n; i++) entityRecords.put(ids[i], new EntityRecord(newArch, newLocs[i], newMask, key));
            } finally {
                if (second != first) second.getResizeLock().unlock();
            }
        } finally {
            first.getResizeLock().unlock();
        }
    }

    // ============ Internal Records ============

    record EntityRecord(Archetype archetype, ArchetypeChunk.ChunkLocation location, ComponentMask mask, SharedValueKey sharedKey) {}

    public record ComponentMetadata(int id, Class<?> type, long size) {}

    public void addComponents(EntityBatch batch, Class<?> componentClass) {
        if (batch == null || batch.size == 0) return;
        addComponents(batch, new Class<?>[]{componentClass});
    }

    public void removeComponents(EntityBatch batch, Class<?> componentClass) {
        if (batch == null || batch.size == 0) return;
        removeComponents(batch, new Class<?>[]{componentClass});
    }

    // New: multi-component batch add/remove/mutate
    public void addComponents(EntityBatch batch, Class<?>... componentClasses) {
        if (batch == null || batch.size == 0 || componentClasses == null || componentClasses.length == 0) return;
        mutateComponents(batch, componentClasses, new Class<?>[0]);
    }

    public void removeComponents(EntityBatch batch, Class<?>... componentClasses) {
        if (batch == null || batch.size == 0 || componentClasses == null || componentClasses.length == 0) return;
        mutateComponents(batch, new Class<?>[0], componentClasses);
    }

    public void mutateComponents(EntityBatch batch, Class<?>[] addClasses, Class<?>[] removeClasses) {
        if (batch == null || batch.size == 0) return;
        // Pre-resolve type ids
        int addCount = addClasses != null ? addClasses.length : 0;
        int remCount = removeClasses != null ? removeClasses.length : 0;
        int[] addTypeIds = new int[addCount];
        int[] remTypeIds = new int[remCount];
        for (int i = 0; i < addCount; i++) {
            Integer tid = componentTypeIds.get(addClasses[i]);
            if (tid == null) throw new IllegalArgumentException("Component type not registered: " + addClasses[i].getName());
            addTypeIds[i] = tid;
        }
        for (int i = 0; i < remCount; i++) {
            Integer tid = componentTypeIds.get(removeClasses[i]);
            if (tid == null) throw new IllegalArgumentException("Component type not registered: " + removeClasses[i].getName());
            remTypeIds[i] = tid;
        }
        // Group by old archetype
        Map<Archetype, List<Integer>> byArchetype = new HashMap<>();
        for (int eid : batch.entityIds) {
            EntityRecord rec = entityRecords.get(eid);
            if (rec == null) continue;
            byArchetype.computeIfAbsent(rec.archetype, k -> new ArrayList<>()).add(eid);
        }
        for (Map.Entry<Archetype, List<Integer>> entry : byArchetype.entrySet()) {
            Archetype oldArch = entry.getKey();
            List<Integer> eids = entry.getValue();
            if (eids.isEmpty()) continue;
            // Build new mask once for this old archetype
            ComponentMask newMask = oldArch.getMask();
            for (int tid : addTypeIds) newMask = newMask.set(tid);
            for (int tid : remTypeIds) newMask = newMask.clear(tid);
            if (newMask.equals(oldArch.getMask())) continue; // nothing to do
            Archetype newArch = archetypeManager.getOrCreateArchetype(newMask);
            // Partition by shared key to keep batch moves localized
            Map<SharedValueKey, List<Integer>> byShared = new HashMap<>();
            for (int eid : eids) {
                EntityRecord rec = entityRecords.get(eid);
                byShared.computeIfAbsent(rec.sharedKey, k -> new ArrayList<>()).add(eid);
            }
            for (Map.Entry<SharedValueKey, List<Integer>> sg : byShared.entrySet()) {
                SharedValueKey key = sg.getKey();
                ChunkGroup oldGroup = oldArch.getChunkGroup(key);
                ChunkGroup newGroup = newArch.getOrCreateChunkGroup(key);
                batchMoveStructural(oldArch, newArch, oldGroup, newGroup, sg.getValue(), oldArch.getMask(), newMask, key);
            }
        }
    }
}

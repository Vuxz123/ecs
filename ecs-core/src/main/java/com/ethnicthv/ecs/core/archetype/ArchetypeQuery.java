package com.ethnicthv.ecs.core.archetype;

import com.ethnicthv.ecs.core.api.archetype.IQuery;
import com.ethnicthv.ecs.core.api.archetype.IQueryBuilder;
import com.ethnicthv.ecs.core.components.Component;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.components.ComponentManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Mutable builder plus executable query implementation for archetype filtering.
 * <p>
 * Calling {@link #build()} returns an immutable snapshot of the current builder
 * configuration. Executing methods directly on this builder also works, but they
 * always operate on a fresh snapshot of the builder state at call time.
 * The low-level entity iteration path exposes {@link ComponentHandle} instances
 * only for unmanaged instance components configured via {@link #with(Class)}.
 */
public final class ArchetypeQuery implements IQueryBuilder, IQuery {
    private final ArchetypeWorld world;
    private final ComponentMask.Builder withMask = ComponentMask.builder();
    private final ComponentMask.Builder withoutMask = ComponentMask.builder();
    private final List<ComponentMask> anyMasks = new ArrayList<>();

    private final List<Class<?>> compList = new ArrayList<>();
    private final List<Integer> compIdxList = new ArrayList<>();

    private Object managedSharedFilter = null;
    private final List<UnmanagedFilter> unmanagedSharedFilters = new ArrayList<>();

    public static final class UnmanagedFilter {
        public final Class<?> type;
        public final long value;

        public UnmanagedFilter(Class<?> type, long value) {
            this.type = type;
            this.value = value;
        }
    }

    private record ResolvedUnmanagedFilter(int typeId, long value) {}

    private record SharedFilterState(
        boolean hasFilters,
        boolean impossible,
        int managedSharedTypeId,
        int managedSharedTicket,
        List<ResolvedUnmanagedFilter> unmanagedFilters
    ) {}

    private record QuerySnapshot(
        ArchetypeWorld world,
        Class<?>[] componentClasses,
        int[] componentTypeIds,
        ComponentMask withMask,
        ComponentMask withoutMask,
        List<ComponentMask> anyMasks,
        Object managedSharedFilter,
        int managedSharedTypeId,
        List<ResolvedUnmanagedFilter> unmanagedSharedFilters
    ) {}

    private final class BuiltQuery implements IQuery {
        private final QuerySnapshot snapshot;

        private BuiltQuery(QuerySnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public void forEach(IQuery.ArchetypeConsumer consumer) {
            executeForEach(snapshot, consumer);
        }

        @Override
        public void forEachChunk(IQuery.ChunkConsumer consumer) {
            executeForEachChunk(snapshot, consumer);
        }

        @Override
        public void forEachEntity(IQuery.EntityConsumer consumer) {
            executeForEachEntity(snapshot, consumer);
        }

        @Override
        public int count() {
            return executeCount(snapshot);
        }

        @Override
        public void forEachParallel(IQuery.EntityConsumer consumer) {
            executeForEachParallel(snapshot, consumer);
        }
    }

    public ArchetypeQuery(ArchetypeWorld world) {
        this.world = world;
    }

    private int requireRegisteredComponentId(Class<?> componentClass) {
        Integer componentTypeId = world.getComponentTypeId(componentClass);
        if (componentTypeId == null) {
            throw new IllegalArgumentException("Component type not registered: " + componentClass.getName());
        }
        return componentTypeId;
    }

    private ComponentDescriptor requireDescriptor(Class<?> componentClass) {
        ComponentDescriptor descriptor = world.getComponentManager().getDescriptor(componentClass);
        if (descriptor == null) {
            throw new IllegalArgumentException("Component descriptor not found: " + componentClass.getName());
        }
        return descriptor;
    }

    private void requireUnmanagedInstanceComponentForEntityIteration(Class<?> componentClass) {
        ComponentDescriptor descriptor = requireDescriptor(componentClass);
        if (descriptor.getKind() != ComponentDescriptor.ComponentKind.INSTANCE_UNMANAGED) {
            throw new IllegalArgumentException(
                "ArchetypeQuery.with() only supports unmanaged instance components for direct handle access: " +
                    componentClass.getName()
            );
        }
        if (componentClass.isAnnotationPresent(Component.Shared.class)) {
            throw new IllegalArgumentException(
                "ArchetypeQuery.with() does not accept @Shared component types; use withShared(...) instead: " +
                    componentClass.getName()
            );
        }
    }

    @Override
    public <T> ArchetypeQuery with(Class<T> componentClass) {
        int componentTypeId = requireRegisteredComponentId(componentClass);
        requireUnmanagedInstanceComponentForEntityIteration(componentClass);
        compList.add(componentClass);
        compIdxList.add(componentTypeId);
        withMask.with(componentTypeId);
        return this;
    }

    @Override
    public <T> ArchetypeQuery without(Class<T> componentClass) {
        withoutMask.with(requireRegisteredComponentId(componentClass));
        return this;
    }

    @Override
    public ArchetypeQuery any(Class<?>... componentClasses) {
        ComponentMask.Builder anyBuilder = ComponentMask.builder();
        for (Class<?> componentClass : componentClasses) {
            anyBuilder.with(requireRegisteredComponentId(componentClass));
        }
        anyMasks.add(anyBuilder.build());
        return this;
    }

    @Override
    public IQueryBuilder withShared(Object managedValue) {
        if (managedValue == null) {
            throw new IllegalArgumentException("managedValue must not be null");
        }
        requireRegisteredComponentId(managedValue.getClass());
        ComponentDescriptor descriptor = requireDescriptor(managedValue.getClass());
        if (descriptor.getKind() != ComponentDescriptor.ComponentKind.SHARED_MANAGED) {
            throw new IllegalArgumentException("Type is not a @Shared @Managed Component type: " + managedValue.getClass().getName());
        }
        this.managedSharedFilter = managedValue;
        return this;
    }

    @Override
    public IQueryBuilder withShared(Class<?> unmanagedSharedType, long value) {
        requireRegisteredComponentId(unmanagedSharedType);
        ComponentDescriptor descriptor = requireDescriptor(unmanagedSharedType);
        if (descriptor.getKind() != ComponentDescriptor.ComponentKind.SHARED_UNMANAGED) {
            throw new IllegalArgumentException("Type is not an @Shared unmanaged Component type: " + unmanagedSharedType.getName());
        }
        unmanagedSharedFilters.add(new UnmanagedFilter(unmanagedSharedType, value));
        return this;
    }

    @Override
    public IQuery build() {
        return new BuiltQuery(captureSnapshot());
    }

    @Override
    public void forEach(IQuery.ArchetypeConsumer consumer) {
        build().forEach(consumer);
    }

    @Override
    public void forEachChunk(IQuery.ChunkConsumer consumer) {
        build().forEachChunk(consumer);
    }

    @Override
    public void forEachEntity(IQuery.EntityConsumer consumer) {
        build().forEachEntity(consumer);
    }

    @Override
    public int count() {
        return build().count();
    }

    @Override
    public void forEachParallel(IQuery.EntityConsumer consumer) {
        build().forEachParallel(consumer);
    }

    private QuerySnapshot captureSnapshot() {
        List<ResolvedUnmanagedFilter> resolvedUnmanagedFilters = new ArrayList<>(unmanagedSharedFilters.size());
        for (UnmanagedFilter filter : unmanagedSharedFilters) {
            resolvedUnmanagedFilters.add(new ResolvedUnmanagedFilter(requireRegisteredComponentId(filter.type), filter.value));
        }

        int managedSharedTypeId = -1;
        if (managedSharedFilter != null) {
            managedSharedTypeId = requireRegisteredComponentId(managedSharedFilter.getClass());
        }

        int[] componentTypeIds = new int[compIdxList.size()];
        for (int i = 0; i < compIdxList.size(); i++) {
            componentTypeIds[i] = compIdxList.get(i);
        }

        Class<?>[] componentClasses = compList.toArray(Class<?>[]::new);
        return new QuerySnapshot(
            world,
            componentClasses,
            componentTypeIds,
            withMask.build(),
            withoutMask.build(),
            List.copyOf(anyMasks),
            managedSharedFilter,
            managedSharedTypeId,
            List.copyOf(resolvedUnmanagedFilters)
        );
    }

    private static void executeForEach(QuerySnapshot snapshot, IQuery.ArchetypeConsumer consumer) {
        SharedFilterState sharedFilterState = resolveSharedFilterState(snapshot);

        for (Archetype archetype : snapshot.world().getAllArchetypes()) {
            if (!matchesArchetype(archetype, snapshot.withMask(), snapshot.withoutMask(), snapshot.anyMasks())) {
                continue;
            }
            if (sharedFilterState.hasFilters()) {
                final boolean[] matched = {false};
                forEachMatchingGroup(archetype, sharedFilterState, group -> matched[0] = true);
                if (!matched[0]) {
                    continue;
                }
            }
            consumer.accept(archetype);
        }
    }

    private static void executeForEachChunk(QuerySnapshot snapshot, IQuery.ChunkConsumer consumer) {
        SharedFilterState sharedFilterState = resolveSharedFilterState(snapshot);

        for (Archetype archetype : snapshot.world().getAllArchetypes()) {
            if (!matchesArchetype(archetype, snapshot.withMask(), snapshot.withoutMask(), snapshot.anyMasks())) {
                continue;
            }
            forEachMatchingGroup(archetype, sharedFilterState, group -> {
                ArchetypeChunk[] chunks = group.getChunksSnapshot();
                int count = group.chunkCount();
                for (int i = 0; i < count; i++) {
                    consumer.accept(chunks[i], archetype);
                }
            });
        }
    }

    private static void executeForEachEntity(QuerySnapshot snapshot, IQuery.EntityConsumer consumer) {
        ComponentManager mgr = snapshot.world().getComponentManager();
        SharedFilterState sharedFilterState = resolveSharedFilterState(snapshot);

        for (Archetype archetype : snapshot.world().getAllArchetypes()) {
            if (!matchesArchetype(archetype, snapshot.withMask(), snapshot.withoutMask(), snapshot.anyMasks())) {
                continue;
            }

            int[] compIndices = resolveComponentIndices(archetype, snapshot.componentTypeIds());
            if (compIndices == null) {
                continue;
            }

            forEachMatchingGroup(archetype, sharedFilterState, group -> {
                ArchetypeChunk[] chunks = group.getChunksSnapshot();
                int count = group.chunkCount();
                for (int ci = 0; ci < count; ci++) {
                    iterateChunkEntities(chunks[ci], mgr, snapshot.componentClasses(), compIndices, consumer, archetype);
                }
            });
        }
    }

    private static int executeCount(QuerySnapshot snapshot) {
        int total = 0;
        SharedFilterState sharedFilterState = resolveSharedFilterState(snapshot);

        for (Archetype archetype : snapshot.world().getAllArchetypes()) {
            if (!matchesArchetype(archetype, snapshot.withMask(), snapshot.withoutMask(), snapshot.anyMasks())) {
                continue;
            }
            if (!sharedFilterState.hasFilters()) {
                total += archetype.getEntityCount();
                continue;
            }
            for (Map.Entry<SharedValueKey, ChunkGroup> entry : archetype.getChunkGroupEntries()) {
                if (matchesSharedKey(archetype, entry.getKey(), sharedFilterState)) {
                    total += entry.getValue().getEntityCount();
                }
            }
        }

        return total;
    }

    private static void executeForEachParallel(QuerySnapshot snapshot, IQuery.EntityConsumer consumer) {
        if (consumer == null) {
            throw new NullPointerException("EntityConsumer must not be null");
        }
        ComponentManager mgr = snapshot.world().getComponentManager();
        SharedFilterState sharedFilterState = resolveSharedFilterState(snapshot);

        for (Archetype archetype : snapshot.world().getAllArchetypes()) {
            if (!matchesArchetype(archetype, snapshot.withMask(), snapshot.withoutMask(), snapshot.anyMasks())) {
                continue;
            }

            int[] compIndices = resolveComponentIndices(archetype, snapshot.componentTypeIds());
            if (compIndices == null) {
                continue;
            }

            forEachMatchingGroup(archetype, sharedFilterState, group -> {
                ArchetypeChunk[] chunks = group.getChunksSnapshot();
                int count = group.chunkCount();
                Arrays.stream(chunks, 0, count).parallel().forEach(chunk ->
                    iterateChunkEntities(chunk, mgr, snapshot.componentClasses(), compIndices, consumer, archetype)
                );
            });
        }
    }

    private static int[] resolveComponentIndices(Archetype archetype, int[] componentTypeIds) {
        int[] compIndices = new int[componentTypeIds.length];
        for (int i = 0; i < componentTypeIds.length; i++) {
            int idx = archetype.indexOfComponentType(componentTypeIds[i]);
            if (idx < 0) {
                return null;
            }
            compIndices[i] = idx;
        }
        return compIndices;
    }

    private static void iterateChunkEntities(
        ArchetypeChunk chunk,
        ComponentManager mgr,
        Class<?>[] componentClasses,
        int[] compIndices,
        IQuery.EntityConsumer consumer,
        Archetype archetype
    ) {
        ComponentHandle[] pooled = new ComponentHandle[compIndices.length];
        for (int k = 0; k < compIndices.length; k++) {
            pooled[k] = mgr.acquireHandle();
        }
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
            for (int k = 0; k < compIndices.length; k++) {
                if (pooled[k] != null) {
                    mgr.releaseHandle(pooled[k]);
                }
            }
        }
    }

    private static boolean matchesArchetype(Archetype archetype, ComponentMask with, ComponentMask without, List<ComponentMask> anyMasks) {
        ComponentMask archetypeMask = archetype.getMask();
        if (!archetypeMask.containsAll(with)) {
            return false;
        }
        if (!archetypeMask.containsNone(without)) {
            return false;
        }
        if (!anyMasks.isEmpty()) {
            boolean matchesAny = false;
            for (ComponentMask anyMask : anyMasks) {
                if (archetypeMask.intersects(anyMask)) {
                    matchesAny = true;
                    break;
                }
            }
            if (!matchesAny) {
                return false;
            }
        }
        return true;
    }

    private static SharedFilterState resolveSharedFilterState(QuerySnapshot snapshot) {
        boolean hasFilters = snapshot.managedSharedFilter() != null || !snapshot.unmanagedSharedFilters().isEmpty();
        if (!hasFilters) {
            return new SharedFilterState(false, false, -1, -1, List.of());
        }

        int managedSharedTicket = -1;
        if (snapshot.managedSharedFilter() != null) {
            managedSharedTicket = snapshot.world().findSharedIndex(snapshot.managedSharedFilter());
            if (managedSharedTicket < 0) {
                return new SharedFilterState(true, true, snapshot.managedSharedTypeId(), -1, List.of());
            }
        }

        return new SharedFilterState(
            true,
            false,
            snapshot.managedSharedTypeId(),
            managedSharedTicket,
            snapshot.unmanagedSharedFilters()
        );
    }

    private static boolean matchesSharedKey(Archetype archetype, SharedValueKey key, SharedFilterState state) {
        if (!state.hasFilters()) {
            return true;
        }
        if (state.impossible()) {
            return false;
        }

        if (state.managedSharedTypeId() >= 0) {
            int pos = archetype.getSharedManagedIndex(state.managedSharedTypeId());
            if (pos < 0) {
                return false;
            }
            int[] managedIndices = key.managedSharedIndices();
            if (managedIndices == null || pos >= managedIndices.length) {
                return false;
            }
            if (managedIndices[pos] != state.managedSharedTicket()) {
                return false;
            }
        }

        for (ResolvedUnmanagedFilter filter : state.unmanagedFilters()) {
            int pos = archetype.getSharedUnmanagedIndex(filter.typeId());
            if (pos < 0) {
                return false;
            }
            long[] unmanagedValues = key.unmanagedSharedValues();
            if (unmanagedValues == null || pos >= unmanagedValues.length) {
                return false;
            }
            if (unmanagedValues[pos] != filter.value()) {
                return false;
            }
        }

        return true;
    }

    private static void forEachMatchingGroup(Archetype archetype, SharedFilterState state, Consumer<ChunkGroup> consumer) {
        if (!state.hasFilters()) {
            for (Map.Entry<SharedValueKey, ChunkGroup> entry : archetype.getChunkGroupEntries()) {
                consumer.accept(entry.getValue());
            }
            return;
        }
        if (state.impossible()) {
            return;
        }
        for (Map.Entry<SharedValueKey, ChunkGroup> entry : archetype.getChunkGroupEntries()) {
            if (matchesSharedKey(archetype, entry.getKey(), state)) {
                consumer.accept(entry.getValue());
            }
        }
    }
}

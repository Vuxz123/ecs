package com.ethnicthv.ecs.core.archetype;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * EntityCommandBuffer – Pillar 4: Write-Fast, Sort-on-Playback.
 *
 * Phase 1: write-side O(1) zero-garbage API backed by per-thread lanes.
 * Phase 2: playback-side O(N log N) sorting and batch execution.
 */
public final class EntityCommandBuffer implements AutoCloseable {
    // ---- Command opcodes ----
    static final int CMD_ADD_COMPONENT = 1;
    static final int CMD_REMOVE_COMPONENT = 2;
    static final int CMD_SET_SHARED_MANAGED = 3;
    static final int CMD_DESTROY_ENTITY = 4;
    static final int CMD_MUTATE_COMPONENTS = 5; // multi add/remove per entity

    // Size in bytes reserved per lane segment
    private static final long LANE_CAPACITY = 64 * 1024; // 64KB

    private final Arena bufferArena;

    // Pool of reusable lanes
    private final ConcurrentLinkedQueue<ECBWriterLane> freeLanes = new ConcurrentLinkedQueue<>();
    // Collected lanes for playback
    private final ConcurrentLinkedQueue<ECBWriterLane> usedLanes = new ConcurrentLinkedQueue<>();

    // Per-thread writer lane
    private final ThreadLocal<ECBWriterLane> threadLocalLane;

    public EntityCommandBuffer(Arena parentArena) {
        // For now reuse parent arena; playback phase may introduce a dedicated arena strategy.
        this.bufferArena = parentArena;
        this.threadLocalLane = ThreadLocal.withInitial(() -> {
            ECBWriterLane lane = freeLanes.poll();
            if (lane == null) {
                lane = new ECBWriterLane(bufferArena.allocate(LANE_CAPACITY));
            } else {
                lane.reset();
            }
            usedLanes.add(lane);
            return lane;
        });
    }

    // Expose a lightweight writer for use in parallel jobs
    public ParallelWriter asParallelWriter(ArchetypeWorld world) {
        return new ParallelWriter(threadLocalLane.get(), world);
    }

    @Override
    public void close() {
        // Lanes memory is owned by bufferArena; do not close arena here.
        freeLanes.clear();
        usedLanes.clear();
    }

    /**
     * Decode all lanes, sort commands, and execute them in batches against the world.
     */
    public void playback(ArchetypeWorld world) {
        // 1) Gather & decode raw lanes into interpreted commands (O(N))
        List<InterpretedCommand> allCommands = new ArrayList<>();
        for (ECBWriterLane lane : usedLanes) {
            long offset = 0L;
            long limit = lane.offset;
            MemorySegment segment = lane.segment;
            while (offset < limit) {
                int cmdId = segment.get(ValueLayout.JAVA_INT, offset);
                switch (cmdId) {
                    case CMD_ADD_COMPONENT: {
                        int eid = segment.get(ValueLayout.JAVA_INT, offset + 4);
                        int tid = segment.get(ValueLayout.JAVA_INT, offset + 8);
                        allCommands.add(InterpretedCommand.addComponent(eid, tid));
                        offset += 12L;
                        break;
                    }
                    case CMD_REMOVE_COMPONENT: {
                        int eid = segment.get(ValueLayout.JAVA_INT, offset + 4);
                        int tid = segment.get(ValueLayout.JAVA_INT, offset + 8);
                        allCommands.add(InterpretedCommand.removeComponent(eid, tid));
                        offset += 12L;
                        break;
                    }
                    case CMD_DESTROY_ENTITY: {
                        int eid = segment.get(ValueLayout.JAVA_INT, offset + 4);
                        allCommands.add(InterpretedCommand.destroyEntity(eid));
                        offset += 8L;
                        break;
                    }
                    case CMD_SET_SHARED_MANAGED: {
                        int eid = segment.get(ValueLayout.JAVA_INT, offset + 4);
                        int tid = segment.get(ValueLayout.JAVA_INT, offset + 8);
                        int sharedIndex = segment.get(ValueLayout.JAVA_INT, offset + 12);
                        allCommands.add(InterpretedCommand.setSharedManaged(eid, tid, sharedIndex));
                        offset += 16L;
                        break;
                    }
                    case CMD_MUTATE_COMPONENTS: {
                        int eid = segment.get(ValueLayout.JAVA_INT, offset + 4);
                        int addCount = segment.get(ValueLayout.JAVA_INT, offset + 8);
                        int remCount = segment.get(ValueLayout.JAVA_INT, offset + 12);
                        int[] addIds = new int[addCount];
                        int[] remIds = new int[remCount];
                        long p = offset + 16L;
                        for (int i = 0; i < addCount; i++) {
                            addIds[i] = segment.get(ValueLayout.JAVA_INT, p);
                            p += 4L;
                        }
                        for (int i = 0; i < remCount; i++) {
                            remIds[i] = segment.get(ValueLayout.JAVA_INT, p);
                            p += 4L;
                        }
                        allCommands.add(InterpretedCommand.mutateComponents(eid, addIds, remIds));
                        offset = p;
                        break;
                    }
                    default:
                        // Unknown or unimplemented command id – skip one int to avoid infinite loop
                        offset += 4L;
                        break;
                }
            }
        }
        // recycle lanes for next frame
        recycleLanes();
        if (allCommands.isEmpty()) return;

        // 2) Sort commands using the massive O(N log N) comparator
        Collections.sort(allCommands);

        // 3) Execute sorted commands in batches via ArchetypeWorld batch API
        executeSortedCommands(allCommands, world);
    }

    /**
     * Return used lanes back to free pool and reset offsets.
     */
    private void recycleLanes() {
        ECBWriterLane lane;
        while ((lane = usedLanes.poll()) != null) {
            lane.reset();
            freeLanes.offer(lane);
        }
    }

    // ---- Inner types ----

    /**
     * One contiguous lane of raw command bytes for a single writer thread.
     * Not thread-safe by design; only the owning thread writes to offset.
     */
    static final class ECBWriterLane {
        MemorySegment segment;
        long offset;

        ECBWriterLane(MemorySegment segment) {
            this.segment = segment;
            this.offset = 0L;
        }

        void reset() {
            this.offset = 0L;
        }

        // Simple ensureCapacity: if not enough space, allocate a fresh segment
        // and swap; old contents are expected to be consumed in playback before
        // reuse. For Phase 1 we keep it simple and single-chunk per lane.
        void ensureCapacity(long needed, Arena arena) {
            long remaining = segment.byteSize() - offset;
            if (remaining >= needed) return;
            // allocate a new chunk and reset offset
            this.segment = arena.allocate(Math.max(LANE_CAPACITY, needed));
            this.offset = 0L;
        }
    }

    /**
     * Lightweight, zero-allocation facade to record commands into a lane.
     */
    public static final class ParallelWriter {
        private final ECBWriterLane lane;
        private final ArchetypeWorld world;

        public ParallelWriter(ECBWriterLane lane, ArchetypeWorld world) {
            this.lane = lane;
            this.world = world;
        }

        public void addComponent(int entityId, Class<?> type) {
            // getComponentTypeId returns Integer; handle unknown types cheaply
            Integer typeId = world.getComponentTypeId(type);
            if (typeId == null) return; // or throw, but write side should be cheap
            long bytes = 12L; // opcode + entityId + typeId
            lane.ensureCapacity(bytes, world.getArena());
            long off = lane.offset;
            MemorySegment seg = lane.segment;
            seg.set(ValueLayout.JAVA_INT, off, CMD_ADD_COMPONENT);
            seg.set(ValueLayout.JAVA_INT, off + 4, entityId);
            seg.set(ValueLayout.JAVA_INT, off + 8, typeId);
            lane.offset = off + bytes;
        }

        public void removeComponent(int entityId, Class<?> type) {
            Integer typeId = world.getComponentTypeId(type);
            if (typeId == null) return;
            long bytes = 12L;
            lane.ensureCapacity(bytes, world.getArena());
            long off = lane.offset;
            MemorySegment seg = lane.segment;
            seg.set(ValueLayout.JAVA_INT, off, CMD_REMOVE_COMPONENT);
            seg.set(ValueLayout.JAVA_INT, off + 4, entityId);
            seg.set(ValueLayout.JAVA_INT, off + 8, typeId);
            lane.offset = off + bytes;
        }

        public void destroyEntity(int entityId) {
            long bytes = 8L; // opcode + entityId
            lane.ensureCapacity(bytes, world.getArena());
            long off = lane.offset;
            MemorySegment seg = lane.segment;
            seg.set(ValueLayout.JAVA_INT, off, CMD_DESTROY_ENTITY);
            seg.set(ValueLayout.JAVA_INT, off + 4, entityId);
            lane.offset = off + bytes;
        }

        // New: record a shared-managed component assignment.
        public void setSharedManaged(int entityId, Object sharedValue) {
            if (sharedValue == null) return;
            Class<?> type = sharedValue.getClass();
            Integer typeId = world.getComponentTypeId(type);
            if (typeId == null) return; // unknown type: ignore at write side
            // Ensure the value has a stable shared index in the store.
            // Using getOrAddSharedIndex here guarantees we never encode -1,
            // and that playback can always reconstruct the value via the store.
            int sharedIndex = world.sharedStore.getOrAddSharedIndex(sharedValue);
            long bytes = 16L; // cmdId + entityId + typeId + sharedIndex
            lane.ensureCapacity(bytes, world.getArena());
            long off = lane.offset;
            MemorySegment seg = lane.segment;
            seg.set(ValueLayout.JAVA_INT, off, CMD_SET_SHARED_MANAGED);
            seg.set(ValueLayout.JAVA_INT, off + 4, entityId);
            seg.set(ValueLayout.JAVA_INT, off + 8, typeId);
            seg.set(ValueLayout.JAVA_INT, off + 12, sharedIndex);
            lane.offset = off + bytes;
        }

        /**
         * Multi-component structural mutation for a single entity: add/remove multiple component types.
         * This encodes a compact command that playback can batch across entities with the same add/remove sets.
         */
        public void mutateComponents(int entityId, Class<?>[] addTypes, Class<?>[] removeTypes) {
            int addCount = addTypes != null ? addTypes.length : 0;
            int remCount = removeTypes != null ? removeTypes.length : 0;
            if (addCount == 0 && remCount == 0) return;
            int[] addIds = new int[addCount];
            int[] remIds = new int[remCount];
            for (int i = 0; i < addCount; i++) {
                Integer tid = world.getComponentTypeId(addTypes[i]);
                if (tid == null) throw new IllegalArgumentException("Unregistered component: " + addTypes[i]);
                addIds[i] = tid;
            }
            for (int i = 0; i < remCount; i++) {
                Integer tid = world.getComponentTypeId(removeTypes[i]);
                if (tid == null) throw new IllegalArgumentException("Unregistered component: " + removeTypes[i]);
                remIds[i] = tid;
            }
            // Encode: [cmdId, entityId, addCount, remCount, addIds..., remIds...]
            long bytes = 16L + 4L * (addCount + remCount);
            lane.ensureCapacity(bytes, world.getArena());
            long off = lane.offset;
            MemorySegment seg = lane.segment;
            seg.set(ValueLayout.JAVA_INT, off, CMD_MUTATE_COMPONENTS);
            seg.set(ValueLayout.JAVA_INT, off + 4, entityId);
            seg.set(ValueLayout.JAVA_INT, off + 8, addCount);
            seg.set(ValueLayout.JAVA_INT, off + 12, remCount);
            long p = off + 16L;
            for (int id : addIds) {
                seg.set(ValueLayout.JAVA_INT, p, id);
                p += 4L;
            }
            for (int id : remIds) {
                seg.set(ValueLayout.JAVA_INT, p, id);
                p += 4L;
            }
            lane.offset = p;
        }
    }

    /**
     * Decoded command representation used for sorting & batching.
     */
    private static final class InterpretedCommand implements Comparable<InterpretedCommand> {
        final int commandId;
        final int entityId;
        final int typeId1;   // primary component type (for add/remove/shared)
        final int typeId2;   // used as sharedIndex for CMD_SET_SHARED_MANAGED
        final int[] addTypeIds;    // for CMD_MUTATE_COMPONENTS
        final int[] removeTypeIds; // for CMD_MUTATE_COMPONENTS

        private InterpretedCommand(int commandId, int entityId, int typeId1, int typeId2,
                                   int[] addTypeIds, int[] removeTypeIds) {
            this.commandId = commandId;
            this.entityId = entityId;
            this.typeId1 = typeId1;
            this.typeId2 = typeId2;
            this.addTypeIds = addTypeIds;
            this.removeTypeIds = removeTypeIds;
        }

        static InterpretedCommand addComponent(int entityId, int typeId) {
            return new InterpretedCommand(CMD_ADD_COMPONENT, entityId, typeId, -1, null, null);
        }

        static InterpretedCommand removeComponent(int entityId, int typeId) {
            return new InterpretedCommand(CMD_REMOVE_COMPONENT, entityId, typeId, -1, null, null);
        }

        static InterpretedCommand destroyEntity(int entityId) {
            return new InterpretedCommand(CMD_DESTROY_ENTITY, entityId, -1, -1, null, null);
        }

        static InterpretedCommand setSharedManaged(int entityId, int typeId, int sharedIndex) {
            return new InterpretedCommand(CMD_SET_SHARED_MANAGED, entityId, typeId, sharedIndex, null, null);
        }

        static InterpretedCommand mutateComponents(int entityId, int[] addIds, int[] remIds) {
            // For sorting/batching, we normalize (sort) the id arrays
            int[] a = addIds != null ? addIds.clone() : new int[0];
            int[] r = remIds != null ? remIds.clone() : new int[0];
            java.util.Arrays.sort(a);
            java.util.Arrays.sort(r);
            return new InterpretedCommand(CMD_MUTATE_COMPONENTS, entityId, 0, 0, a, r);
        }

        @Override
        public int compareTo(InterpretedCommand other) {
            // 1) Destroy trước
            boolean thisDestroy = this.commandId == CMD_DESTROY_ENTITY;
            boolean otherDestroy = other.commandId == CMD_DESTROY_ENTITY;
            if (thisDestroy && !otherDestroy) return -1;
            if (!thisDestroy && otherDestroy) return 1;

            // 2) Gom theo loại lệnh
            int c = Integer.compare(this.commandId, other.commandId);
            if (c != 0) return c;

            // 3) Với multi-mutate, gom theo add/remove sets
            if (this.commandId == CMD_MUTATE_COMPONENTS) {
                int lenA = this.addTypeIds.length;
                int lenB = other.addTypeIds.length;
                c = Integer.compare(lenA, lenB);
                if (c != 0) return c;
                for (int i = 0; i < lenA; i++) {
                    c = Integer.compare(this.addTypeIds[i], other.addTypeIds[i]);
                    if (c != 0) return c;
                }
                int lenRA = this.removeTypeIds.length;
                int lenRB = other.removeTypeIds.length;
                c = Integer.compare(lenRA, lenRB);
                if (c != 0) return c;
                for (int i = 0; i < lenRA; i++) {
                    c = Integer.compare(this.removeTypeIds[i], other.removeTypeIds[i]);
                    if (c != 0) return c;
                }
            } else {
                // 3b) Các loại lệnh khác gom theo typeId1 (component type) và sharedIndex nếu cần
                c = Integer.compare(this.typeId1, other.typeId1);
                if (c != 0) return c;
                if (this.commandId == CMD_SET_SHARED_MANAGED) {
                    c = Integer.compare(this.typeId2, other.typeId2);
                    if (c != 0) return c;
                }
            }

            // 4) Cuối cùng theo entity id
            return Integer.compare(this.entityId, other.entityId);
        }
    }

    /**
     * Key describing a batch of structural mutations by command kind and component type.
     */
    private static final class MutateKey {
        final int commandId;
        final int typeId;
        final int sharedIndex; // only meaningful for CMD_SET_SHARED_MANAGED
        final int[] addTypeIds;    // for CMD_MUTATE_COMPONENTS
        final int[] removeTypeIds; // for CMD_MUTATE_COMPONENTS

        MutateKey(int commandId, int typeId, int sharedIndex) {
            this(commandId, typeId, sharedIndex, null, null);
        }

        MutateKey(int commandId, int typeId, int sharedIndex, int[] addTypeIds, int[] removeTypeIds) {
            this.commandId = commandId;
            this.typeId = typeId;
            this.sharedIndex = sharedIndex;
            this.addTypeIds = addTypeIds;
            this.removeTypeIds = removeTypeIds;
        }

        boolean isDestroy() { return commandId == CMD_DESTROY_ENTITY; }
        boolean isAdd()     { return commandId == CMD_ADD_COMPONENT; }
        boolean isRemove()  { return commandId == CMD_REMOVE_COMPONENT; }
        boolean isSetShared() { return commandId == CMD_SET_SHARED_MANAGED; }
        boolean isMutateMulti() { return commandId == CMD_MUTATE_COMPONENTS; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MutateKey other)) return false;
            if (commandId != other.commandId) return false;
            if (commandId == CMD_MUTATE_COMPONENTS) {
                return java.util.Arrays.equals(this.addTypeIds, other.addTypeIds)
                        && java.util.Arrays.equals(this.removeTypeIds, other.removeTypeIds);
            }
            return typeId == other.typeId && sharedIndex == other.sharedIndex;
        }

        @Override
        public int hashCode() {
            int result = commandId;
            if (commandId == CMD_MUTATE_COMPONENTS) {
                result = 31 * result + java.util.Arrays.hashCode(addTypeIds);
                result = 31 * result + java.util.Arrays.hashCode(removeTypeIds);
            } else {
                result = 31 * result + typeId;
                result = 31 * result + sharedIndex;
            }
            return result;
        }
    }

    private void executeSortedCommands(List<InterpretedCommand> sortedCommands, ArchetypeWorld world) {
        List<Integer> entityBatch = new ArrayList<>();
        MutateKey currentKey = null;

        for (InterpretedCommand cmd : sortedCommands) {
            MutateKey cmdKey;
            if (cmd.commandId == CMD_MUTATE_COMPONENTS) {
                cmdKey = new MutateKey(cmd.commandId, 0, 0, cmd.addTypeIds, cmd.removeTypeIds);
            } else {
                int sharedIndex = (cmd.commandId == CMD_SET_SHARED_MANAGED) ? cmd.typeId2 : -1;
                cmdKey = new MutateKey(cmd.commandId, cmd.typeId1, sharedIndex);
            }
            if (currentKey != null && cmdKey.equals(currentKey)) {
                entityBatch.add(cmd.entityId);
            } else {
                if (!entityBatch.isEmpty()) {
                    flushBatch(currentKey, entityBatch, world);
                }
                entityBatch.clear();
                entityBatch.add(cmd.entityId);
                currentKey = cmdKey;
            }
        }
        if (!entityBatch.isEmpty() && currentKey != null) {
            flushBatch(currentKey, entityBatch, world);
        }
    }

    private void flushBatch(MutateKey key, List<Integer> entityBatch, ArchetypeWorld world) {
        int size = entityBatch.size();
        int[] ids = new int[size];
        for (int i = 0; i < size; i++) ids[i] = entityBatch.get(i);
        ArchetypeWorld.EntityBatch batch = ArchetypeWorld.EntityBatch.of(ids);

        if (key.isDestroy()) {
            for (int id : ids) {
                world.destroyEntity(id);
            }
        } else if (key.isAdd()) {
            Class<?> componentClass = world.getComponentMetadata(key.typeId).type();
            world.addComponents(batch, componentClass);
        } else if (key.isRemove()) {
            Class<?> componentClass = world.getComponentMetadata(key.typeId).type();
            world.removeComponents(batch, componentClass);
        } else if (key.isSetShared()) {
            Object sharedValue = world.sharedStore.getValue(key.sharedIndex);
            if (sharedValue != null) {
                // Use batch shared API to leverage O(M + N*M_intersect) path.
                world.setSharedComponent(batch, sharedValue);
            }
        } else if (key.isMutateMulti()) {
            // Multi-component mutate: map type ids back to classes and delegate to ArchetypeWorld
            Class<?>[] addClasses = new Class<?>[key.addTypeIds.length];
            for (int i = 0; i < key.addTypeIds.length; i++) {
                addClasses[i] = world.getComponentMetadata(key.addTypeIds[i]).type();
            }
            Class<?>[] remClasses = new Class<?>[key.removeTypeIds.length];
            for (int i = 0; i < key.removeTypeIds.length; i++) {
                remClasses[i] = world.getComponentMetadata(key.removeTypeIds[i]).type();
            }
            world.mutateComponents(batch, addClasses, remClasses);
        }
    }
}

package com.ethnicthv.ecs.core.archetype;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * EntityCommandBuffer - write-fast, sort-on-playback command buffer.
 */
public final class EntityCommandBuffer implements AutoCloseable {
    static final int CMD_ADD_COMPONENT = 1;
    static final int CMD_REMOVE_COMPONENT = 2;
    static final int CMD_SET_SHARED_MANAGED = 3;
    static final int CMD_DESTROY_ENTITY = 4;
    static final int CMD_MUTATE_COMPONENTS = 5;

    private static final long LANE_CAPACITY = 64 * 1024;

    private final Arena bufferArena;
    private final ConcurrentLinkedQueue<ECBWriterLane> freeLanes = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ECBWriterLane> usedLanes = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<ECBWriterLane> threadLocalLane;

    public EntityCommandBuffer(Arena parentArena) {
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

    public ParallelWriter asParallelWriter(ArchetypeWorld world) {
        return new ParallelWriter(threadLocalLane.get(), world);
    }

    @Override
    public void close() {
        freeLanes.clear();
        usedLanes.clear();
    }

    public void playback(ArchetypeWorld world) {
        List<InterpretedCommand> allCommands = new ArrayList<>();
        for (ECBWriterLane lane : usedLanes) {
            for (int i = 0; i < lane.completedSegments.size(); i++) {
                decodeSegment(lane.completedSegments.get(i), lane.completedLimits.get(i), allCommands);
            }
            decodeSegment(lane.segment, lane.offset, allCommands);
        }

        recycleLanes();
        if (allCommands.isEmpty()) return;

        Collections.sort(allCommands);
        executeSortedCommands(allCommands, world);
    }

    private void decodeSegment(MemorySegment segment, long limit, List<InterpretedCommand> allCommands) {
        long offset = 0L;
        while (offset < limit) {
            int cmdId = segment.get(ValueLayout.JAVA_INT, offset);
            switch (cmdId) {
                case CMD_ADD_COMPONENT -> {
                    int eid = segment.get(ValueLayout.JAVA_INT, offset + 4);
                    int tid = segment.get(ValueLayout.JAVA_INT, offset + 8);
                    allCommands.add(InterpretedCommand.addComponent(eid, tid));
                    offset += 12L;
                }
                case CMD_REMOVE_COMPONENT -> {
                    int eid = segment.get(ValueLayout.JAVA_INT, offset + 4);
                    int tid = segment.get(ValueLayout.JAVA_INT, offset + 8);
                    allCommands.add(InterpretedCommand.removeComponent(eid, tid));
                    offset += 12L;
                }
                case CMD_DESTROY_ENTITY -> {
                    int eid = segment.get(ValueLayout.JAVA_INT, offset + 4);
                    allCommands.add(InterpretedCommand.destroyEntity(eid));
                    offset += 8L;
                }
                case CMD_SET_SHARED_MANAGED -> {
                    int eid = segment.get(ValueLayout.JAVA_INT, offset + 4);
                    int tid = segment.get(ValueLayout.JAVA_INT, offset + 8);
                    int sharedIndex = segment.get(ValueLayout.JAVA_INT, offset + 12);
                    allCommands.add(InterpretedCommand.setSharedManaged(eid, tid, sharedIndex));
                    offset += 16L;
                }
                case CMD_MUTATE_COMPONENTS -> {
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
                }
                default -> offset += 4L;
            }
        }
    }

    private void recycleLanes() {
        ECBWriterLane lane;
        while ((lane = usedLanes.poll()) != null) {
            lane.reset();
            freeLanes.offer(lane);
        }
    }

    static final class ECBWriterLane {
        final List<MemorySegment> completedSegments = new ArrayList<>();
        final List<Long> completedLimits = new ArrayList<>();
        MemorySegment segment;
        long offset;

        ECBWriterLane(MemorySegment segment) {
            this.segment = segment;
            this.offset = 0L;
        }

        void reset() {
            completedSegments.clear();
            completedLimits.clear();
            offset = 0L;
        }

        void ensureCapacity(long needed, Arena arena) {
            long remaining = segment.byteSize() - offset;
            if (remaining >= needed) return;
            completedSegments.add(segment);
            completedLimits.add(offset);
            segment = arena.allocate(Math.max(LANE_CAPACITY, needed));
            offset = 0L;
        }
    }

    public static final class ParallelWriter {
        private final ECBWriterLane lane;
        private final ArchetypeWorld world;

        public ParallelWriter(ECBWriterLane lane, ArchetypeWorld world) {
            this.lane = lane;
            this.world = world;
        }

        public void addComponent(int entityId, Class<?> type) {
            Integer typeId = world.getComponentTypeId(type);
            if (typeId == null) return;
            long bytes = 12L;
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
            long bytes = 8L;
            lane.ensureCapacity(bytes, world.getArena());
            long off = lane.offset;
            MemorySegment seg = lane.segment;
            seg.set(ValueLayout.JAVA_INT, off, CMD_DESTROY_ENTITY);
            seg.set(ValueLayout.JAVA_INT, off + 4, entityId);
            lane.offset = off + bytes;
        }

        public void setSharedManaged(int entityId, Object sharedValue) {
            if (sharedValue == null) return;
            Class<?> type = sharedValue.getClass();
            Integer typeId = world.getComponentTypeId(type);
            if (typeId == null) return;
            int sharedIndex = world.sharedStore.getOrAddSharedIndex(sharedValue);
            long bytes = 16L;
            lane.ensureCapacity(bytes, world.getArena());
            long off = lane.offset;
            MemorySegment seg = lane.segment;
            seg.set(ValueLayout.JAVA_INT, off, CMD_SET_SHARED_MANAGED);
            seg.set(ValueLayout.JAVA_INT, off + 4, entityId);
            seg.set(ValueLayout.JAVA_INT, off + 8, typeId);
            seg.set(ValueLayout.JAVA_INT, off + 12, sharedIndex);
            lane.offset = off + bytes;
        }

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

    private static final class InterpretedCommand implements Comparable<InterpretedCommand> {
        final int commandId;
        final int entityId;
        final int typeId1;
        final int typeId2;
        final int[] addTypeIds;
        final int[] removeTypeIds;

        private InterpretedCommand(int commandId, int entityId, int typeId1, int typeId2, int[] addTypeIds, int[] removeTypeIds) {
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
            int[] normalizedAdds = addIds != null ? addIds.clone() : new int[0];
            int[] normalizedRemoves = remIds != null ? remIds.clone() : new int[0];
            java.util.Arrays.sort(normalizedAdds);
            java.util.Arrays.sort(normalizedRemoves);
            return new InterpretedCommand(CMD_MUTATE_COMPONENTS, entityId, 0, 0, normalizedAdds, normalizedRemoves);
        }

        @Override
        public int compareTo(InterpretedCommand other) {
            boolean thisDestroy = this.commandId == CMD_DESTROY_ENTITY;
            boolean otherDestroy = other.commandId == CMD_DESTROY_ENTITY;
            if (thisDestroy && !otherDestroy) return -1;
            if (!thisDestroy && otherDestroy) return 1;

            int c = Integer.compare(this.commandId, other.commandId);
            if (c != 0) return c;

            if (this.commandId == CMD_MUTATE_COMPONENTS) {
                c = Integer.compare(this.addTypeIds.length, other.addTypeIds.length);
                if (c != 0) return c;
                for (int i = 0; i < this.addTypeIds.length; i++) {
                    c = Integer.compare(this.addTypeIds[i], other.addTypeIds[i]);
                    if (c != 0) return c;
                }
                c = Integer.compare(this.removeTypeIds.length, other.removeTypeIds.length);
                if (c != 0) return c;
                for (int i = 0; i < this.removeTypeIds.length; i++) {
                    c = Integer.compare(this.removeTypeIds[i], other.removeTypeIds[i]);
                    if (c != 0) return c;
                }
            } else {
                c = Integer.compare(this.typeId1, other.typeId1);
                if (c != 0) return c;
                if (this.commandId == CMD_SET_SHARED_MANAGED) {
                    c = Integer.compare(this.typeId2, other.typeId2);
                    if (c != 0) return c;
                }
            }

            return Integer.compare(this.entityId, other.entityId);
        }
    }

    private static final class MutateKey {
        final int commandId;
        final int typeId;
        final int sharedIndex;
        final int[] addTypeIds;
        final int[] removeTypeIds;

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
        boolean isAdd() { return commandId == CMD_ADD_COMPONENT; }
        boolean isRemove() { return commandId == CMD_REMOVE_COMPONENT; }
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
                int sharedIndex = cmd.commandId == CMD_SET_SHARED_MANAGED ? cmd.typeId2 : -1;
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
                world.setSharedComponent(batch, sharedValue);
            }
        } else if (key.isMutateMulti()) {
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

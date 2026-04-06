package com.ethnicthv.ecs.components;

import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.demo.ReflectionFixedArrayComponentHandle;
import com.ethnicthv.ecs.demo.ReflectionFixedArrayComponentMeta;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.*;

final class FixedArrayHandleTest {
    @Test
    void rawHandleSupportsIndexedArrayAccess() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(ReflectionFixedArrayComponentMeta.DESCRIPTOR.getTotalSize());
            ComponentHandle handle = new ComponentHandle(segment, ReflectionFixedArrayComponentMeta.DESCRIPTOR);

            int samplesIndex = ReflectionFixedArrayComponentMeta.IDX_SAMPLES;
            int countIndex = ReflectionFixedArrayComponentMeta.IDX_COUNT;

            handle.setInt(samplesIndex, 0, 11);
            handle.setInt(samplesIndex, 1, 22);
            handle.setInt(samplesIndex, 2, 33);
            handle.setInt(samplesIndex, 3, 44);
            handle.setInt(countIndex, 0, 4);

            assertEquals(11, handle.getInt(samplesIndex, 0));
            assertEquals(22, handle.getInt(samplesIndex, 1));
            assertEquals(33, handle.getInt(samplesIndex, 2));
            assertEquals(44, handle.getInt(samplesIndex, 3));
            assertEquals(4, handle.getInt(countIndex));
            assertEquals(4, handle.getInt(countIndex, 0));

            assertEquals(11, segment.get(ValueLayout.JAVA_INT, 0L));
            assertEquals(22, segment.get(ValueLayout.JAVA_INT, 4L));
            assertEquals(33, segment.get(ValueLayout.JAVA_INT, 8L));
            assertEquals(44, segment.get(ValueLayout.JAVA_INT, 12L));
            assertEquals(4, segment.get(ValueLayout.JAVA_INT, 16L));
        }
    }

    @Test
    void rawHandleRejectsOutOfBoundsAndNonIndexedGenericArrayAccess() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(ReflectionFixedArrayComponentMeta.DESCRIPTOR.getTotalSize());
            ComponentHandle handle = new ComponentHandle(segment, ReflectionFixedArrayComponentMeta.DESCRIPTOR);

            int samplesIndex = ReflectionFixedArrayComponentMeta.IDX_SAMPLES;

            assertThrows(IndexOutOfBoundsException.class, () -> handle.getInt(samplesIndex, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> handle.getInt(samplesIndex, 4));
            assertThrows(IllegalArgumentException.class, () -> handle.getByIndex(samplesIndex));
            assertThrows(IllegalArgumentException.class, () -> handle.setByIndex(samplesIndex, 123));
        }
    }

    @Test
    void generatedHandleExposesIndexedArrayAccessors() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(ReflectionFixedArrayComponentMeta.DESCRIPTOR.getTotalSize());
            ComponentHandle raw = new ComponentHandle(segment, ReflectionFixedArrayComponentMeta.DESCRIPTOR);
            ReflectionFixedArrayComponentHandle handle = new ReflectionFixedArrayComponentHandle();
            handle.__bind(raw);

            assertEquals(4, handle.lengthOfSamples());

            handle.setSamples(0, 101);
            handle.setSamples(1, 202);
            handle.setSamples(2, 303);
            handle.setSamples(3, 404);
            handle.setCount(4);

            assertEquals(101, handle.getSamples(0));
            assertEquals(202, handle.getSamples(1));
            assertEquals(303, handle.getSamples(2));
            assertEquals(404, handle.getSamples(3));
            assertEquals(4, handle.getCount());
            assertThrows(IndexOutOfBoundsException.class, () -> handle.getSamples(4));
        }
    }
}

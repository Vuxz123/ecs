package com.ethnicthv.ecs.components;

import com.ethnicthv.ecs.core.components.Component;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.demo.LegacySizedScalarComponent;
import com.ethnicthv.ecs.demo.ReflectionFixedArrayComponent;
import com.ethnicthv.ecs.demo.ReflectionPaddedArrayComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class FixedArrayDescriptorTest {
    @Test
    void reflectionDescriptorTracksFixedArraySizeAndOffsets() {
        ComponentManager componentManager = new ComponentManager();
        componentManager.registerComponent(ReflectionFixedArrayComponent.class);

        ComponentDescriptor descriptor = componentManager.getDescriptor(ReflectionFixedArrayComponent.class);
        assertNotNull(descriptor);
        assertEquals(20L, descriptor.getTotalSize());

        ComponentDescriptor.FieldDescriptor samples = descriptor.getField("samples");
        ComponentDescriptor.FieldDescriptor count = descriptor.getField("count");

        assertNotNull(samples);
        assertEquals(ComponentDescriptor.FieldType.INT, samples.type());
        assertEquals(4, samples.elementCount());
        assertTrue(samples.isArray());
        assertEquals(4L, samples.elementSize());
        assertEquals(16L, samples.size());
        assertEquals(0L, samples.offset());

        assertNotNull(count);
        assertEquals(1, count.elementCount());
        assertFalse(count.isArray());
        assertEquals(4L, count.elementSize());
        assertEquals(16L, count.offset());
    }

    @Test
    void reflectionPaddingLayoutUsesTotalArrayStorageForNextFieldOffset() {
        ComponentManager componentManager = new ComponentManager();
        componentManager.registerComponent(ReflectionPaddedArrayComponent.class);

        ComponentDescriptor descriptor = componentManager.getDescriptor(ReflectionPaddedArrayComponent.class);
        assertNotNull(descriptor);
        assertEquals(16L, descriptor.getTotalSize());

        ComponentDescriptor.FieldDescriptor samples = descriptor.getField("samples");
        ComponentDescriptor.FieldDescriptor tail = descriptor.getField("tail");

        assertNotNull(samples);
        assertEquals(3, samples.elementCount());
        assertEquals(3L, samples.size());
        assertEquals(1L, samples.elementSize());
        assertEquals(0L, samples.offset());

        assertNotNull(tail);
        assertEquals(8L, tail.offset());
        assertEquals(1, tail.elementCount());
    }

    @Test
    void invalidDescriptorArrayMetadataIsRejectedAtRegistration() {
        ComponentManager componentManager = new ComponentManager();
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> componentManager.registerComponentWithDescriptor(
                ReflectionFixedArrayComponent.class,
                new ComponentDescriptor(
                    ReflectionFixedArrayComponent.class,
                    4L,
                    java.util.List.of(
                        new ComponentDescriptor.FieldDescriptor("samples", ComponentDescriptor.FieldType.INT, 0L, 1L, 4, 2)
                    ),
                    Component.LayoutType.SEQUENTIAL
                )
            )
        );
        assertTrue(exception.getMessage().contains("smaller than elementCount"));
    }

    @Test
    void scalarFieldsCanStillUseLegacySizeOverride() {
        ComponentManager componentManager = new ComponentManager();
        assertDoesNotThrow(() -> componentManager.registerComponent(LegacySizedScalarComponent.class));

        ComponentDescriptor descriptor = componentManager.getDescriptor(LegacySizedScalarComponent.class);
        assertNotNull(descriptor);

        ComponentDescriptor.FieldDescriptor id = descriptor.getField("id");
        assertNotNull(id);
        assertEquals(1, id.elementCount());
        assertEquals(2L, id.size());
        assertFalse(id.isArray());
    }

}

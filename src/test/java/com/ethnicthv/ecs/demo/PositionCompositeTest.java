package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.components.ComponentManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests recursive flattening of composite field Vector3f inside PositionComponent.
 */
public class PositionCompositeTest {
    static ComponentManager mgr;
    static final Arena arena = Arena.ofAuto();

    @BeforeAll
    static void setup() {
        mgr = new ComponentManager();
        mgr.registerComponent(PositionComponent.class);
    }

    @Test
    @DisplayName("Flattened descriptor for PositionComponent exposes pos_x,pos_y,pos_z with correct offsets")
    void descriptorFlattening() {
        ComponentDescriptor d = PositionComponentMeta.DESCRIPTOR;
        assertEquals(5, d.fieldCount(), "Expected 5 flattened fields (x,y,pos_x,pos_y,pos_z)");
        assertEquals("x", d.getField(0).name());
        assertEquals(0L, d.getField(0).offset());
        assertEquals("y", d.getField(1).name());
        assertEquals(4L, d.getField(1).offset());
        assertEquals("pos_x", d.getField(2).name());
        assertEquals(8L, d.getField(2).offset());
        assertEquals("pos_y", d.getField(3).name());
        assertEquals(12L, d.getField(3).offset());
        assertEquals("pos_z", d.getField(4).name());
        assertEquals(16L, d.getField(4).offset());
        assertEquals(20L, d.getTotalSize());
    }

    @Test
    @DisplayName("PositionComponentHandle read/write flattened composite values")
    void handleAccess() {
        ComponentDescriptor d = PositionComponentMeta.DESCRIPTOR;
        MemorySegment seg = arena.allocate(d.getTotalSize());
        ComponentHandle raw = new ComponentHandle(seg, d);
        PositionComponentHandle h = new PositionComponentHandle();
        h.__bind(raw);

        // write values
        h.setX(1.5f);
        h.setY(2.5f);
        h.setPosX(10f);
        h.setPosY(11f);
        h.setPosZ(12f);

        // read back
        assertEquals(1.5f, h.getX());
        assertEquals(2.5f, h.getY());
        assertEquals(10f, h.getPosX());
        assertEquals(11f, h.getPosY());
        assertEquals(12f, h.getPosZ());

        // check actual memory layout
        assertEquals(1.5f, seg.get(ValueLayout.JAVA_FLOAT, 0));
        assertEquals(2.5f, seg.get(ValueLayout.JAVA_FLOAT, 4));
        assertEquals(10f, seg.get(ValueLayout.JAVA_FLOAT, 8));
        assertEquals(11f, seg.get(ValueLayout.JAVA_FLOAT, 12));
        assertEquals(12f, seg.get(ValueLayout.JAVA_FLOAT, 16));
    }

    @Test
    @DisplayName("Slice getter getPos() returns a Vector3fHandle bound to same segment base offset")
    void sliceGetter() {
        ComponentDescriptor d = PositionComponentMeta.DESCRIPTOR;
        MemorySegment seg = arena.allocate(d.getTotalSize());
        ComponentHandle raw = new ComponentHandle(seg, d);
        PositionComponentHandle posHandle = new PositionComponentHandle();
        posHandle.__bind(raw);

        // Use slice handle
        Vector3fHandle vHandle = posHandle.getPos();
        assertNotNull(vHandle, "Slice handle should not be null");

        // Write through slice
        vHandle.setX(3f); // should map to offset 8
        vHandle.setY(4f); // offset 12
        vHandle.setZ(5f); // offset 16

        assertEquals(3f, seg.get(ValueLayout.JAVA_FLOAT, 8));
        assertEquals(4f, seg.get(ValueLayout.JAVA_FLOAT, 12));
        assertEquals(5f, seg.get(ValueLayout.JAVA_FLOAT, 16));

        // Ensure parent flattened accessors see same values
        assertEquals(3f, posHandle.getPosX());
        assertEquals(4f, posHandle.getPosY());
        assertEquals(5f, posHandle.getPosZ());
    }
}

package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.demo.PositionComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test from ComponentProcessor -> generated meta/handle -> ArchetypeWorld.addComponent
 * using a simple test component and its generated typed handle.
 */
public class ArchetypeWorldTypedHandleTest {

    @Test
    public void addComponent_initializer_writesDirectlyToChunkMemory() {
        ComponentManager cm = new ComponentManager();
        // Register descriptor + typed handle pool for PositionComponent and TestComponent1
        cm.registerComponentWithHandle(TestComponent1.class, TestComponent1Meta.DESCRIPTOR, TestComponent1Handle::new);

        ArchetypeWorld world = new ArchetypeWorld(cm);
        world.registerComponent(TestComponent1.class);

        int entityId = world.createEntity();

        // Act: add TestComponent1 via typed handle initializer
        world.addComponent(entityId, TestComponent1.class, (TestComponent1Handle h) -> {
            h.setValue(42);
        });

        assertTrue(world.hasComponent(entityId, TestComponent1.class));

        var seg = world.getComponent(entityId, TestComponent1.class);
        assertNotNull(seg, "Component MemorySegment should not be null after addComponent initializer");

        var rawHandle = cm.createHandle(TestComponent1.class, seg);
        try {
            TestComponent1Handle handle = new TestComponent1Handle();
            handle.__bind(rawHandle);

            int v = handle.getValue();
            assertEquals(42, v);
        } finally {
            cm.releaseHandle(rawHandle);
            world.close();
        }
    }
}

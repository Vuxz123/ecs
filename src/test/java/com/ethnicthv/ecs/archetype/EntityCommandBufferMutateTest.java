package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.archetype.EntityCommandBuffer;
import com.ethnicthv.ecs.core.components.ComponentManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CMD_MUTATE_COMPONENTS in EntityCommandBuffer: batch add/remove multiple types.
 */
public class EntityCommandBufferMutateTest {

    private ArchetypeWorld world;

    @BeforeEach
    void setUp() {
        world = new ArchetypeWorld(new ComponentManager());
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TestComponent2.class);
        world.registerComponent(TestComponent3.class);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void ecb_multiMutate_addAndRemoveComponents_inBatch() throws Exception {
        int n = 16;
        int[] eids = new int[n];
        for (int i = 0; i < n; i++) {
            eids[i] = world.createEntity(TestComponent1.class);
        }

        Method getArena = ArchetypeWorld.class.getDeclaredMethod("getArena");
        getArena.setAccessible(true);
        Arena arena = (Arena) getArena.invoke(world);

        EntityCommandBuffer ecb = new EntityCommandBuffer(arena);
        EntityCommandBuffer.ParallelWriter writer = ecb.asParallelWriter(world);

        // For all entities: add TestComponent2 and TestComponent3, remove TestComponent1
        Class<?>[] adds = new Class<?>[]{TestComponent2.class, TestComponent3.class};
        Class<?>[] rems = new Class<?>[]{TestComponent1.class};
        for (int eid : eids) {
            writer.mutateComponents(eid, adds, rems);
        }

        ecb.playback(world);

        // After playback, each entity should have T2 & T3 and no longer have T1
        for (int eid : eids) {
            assertFalse(world.hasComponent(eid, TestComponent1.class), "T1 should be removed by multi-mutate");
            assertTrue(world.hasComponent(eid, TestComponent2.class), "T2 should be added by multi-mutate");
            assertTrue(world.hasComponent(eid, TestComponent3.class), "T3 should be added by multi-mutate");
        }
    }
}


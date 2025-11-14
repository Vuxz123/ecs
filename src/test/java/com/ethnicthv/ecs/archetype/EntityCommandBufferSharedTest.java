package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.archetype.EntityCommandBuffer;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.components.SharedComponentStore;
import com.ethnicthv.ecs.demo.TeamShared;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ECB tests focused on shared-managed commands:
 * - Multiple setSharedManaged with the same shared value must batch into a single shared group change.
 * - Shared index lookup for an existing value should not create a new index.
 */
public class EntityCommandBufferSharedTest {

    private ArchetypeWorld world;

    @BeforeEach
    void setUp() {
        world = new ArchetypeWorld(new ComponentManager());
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TeamShared.class);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void ecb_sharedManaged_sameValue_batchesAndReusesIndex() throws Exception {
        int n = 8;
        int[] eids = new int[n];
        for (int i = 0; i < n; i++) {
            eids[i] = world.createEntity(TestComponent1.class);
        }

        TeamShared teamBlue = new TeamShared("Blue");
        int probeId = world.createEntity(TestComponent1.class);
        world.setSharedComponent(probeId, teamBlue);

        Field storeField = ArchetypeWorld.class.getDeclaredField("sharedStore");
        storeField.setAccessible(true);
        SharedComponentStore store = (SharedComponentStore) storeField.get(world);

        int initialIndex = store.findIndex(teamBlue);
        assertTrue(initialIndex >= 0, "Expected TeamShared value to be present in shared store");

        Method getArena = ArchetypeWorld.class.getDeclaredMethod("getArena");
        getArena.setAccessible(true);
        Arena arena = (Arena) getArena.invoke(world);

        EntityCommandBuffer ecb = new EntityCommandBuffer(arena);
        EntityCommandBuffer.ParallelWriter writer = ecb.asParallelWriter(world);
        for (int eid : eids) {
            writer.setSharedManaged(eid, teamBlue);
        }

        ecb.playback(world);

        int indexAfter = store.findIndex(teamBlue);
        assertEquals(initialIndex, indexAfter, "Shared index for TeamShared should be reused, not recreated");

        // Validate semantics via shared-query: all ECB entities + probe entity must be in Blue group
        AtomicInteger countBlue = new AtomicInteger();
        world.query().withShared(new TeamShared("Blue")).build()
                .forEachEntity((id, handles, arch) -> countBlue.incrementAndGet());

        assertEquals(n + 1, countBlue.get(),
                "All ECB entities plus the probe entity should be in the Blue shared group");
    }
}

package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.api.archetype.IQuery;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.demo.TeamShared;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for managed and unmanaged shared components: assignment, filtering, and dynamic addition.
 */
public class SharedComponentTest {

    private ArchetypeWorld world;

    @BeforeEach
    void setUp() {
        world = new ArchetypeWorld(new ComponentManager());
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void managedShared_basicFilter_sequential() {
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TeamShared.class);

        int a = 12, b = 15;
        for (int i = 0; i < a + b; i++) {
            int eid = world.createEntity(TestComponent1.class);
            world.setSharedComponent(eid, (i < a) ? new TeamShared("A") : new TeamShared("B"));
        }

        IQuery qA = world.query().withShared(new TeamShared("A")).build();
        AtomicInteger cntA = new AtomicInteger();
        qA.forEachEntity((id, handles, arch) -> cntA.incrementAndGet());
        assertEquals(a, cntA.get(), "Managed shared filter A should match A-count only");

        IQuery qB = world.query().withShared(new TeamShared("B")).build();
        AtomicInteger cntB = new AtomicInteger();
        qB.forEachEntity((id, handles, arch) -> cntB.incrementAndGet());
        assertEquals(b, cntB.get(), "Managed shared filter B should match B-count only");
    }

    @Test
    void managedShared_changeValue_movesGroup_andFilters() {
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TeamShared.class);

        int eid = world.createEntity(TestComponent1.class);
        world.setSharedComponent(eid, new TeamShared("A"));

        AtomicInteger cnt = new AtomicInteger();
        world.query().withShared(new TeamShared("A")).build().forEachEntity((_, __, ___) -> cnt.incrementAndGet());
        assertEquals(1, cnt.get());

        // Change to B and verify A no longer matches, B matches
        world.setSharedComponent(eid, new TeamShared("B"));
        AtomicInteger cntA2 = new AtomicInteger();
        world.query().withShared(new TeamShared("A")).build().forEachEntity((_, __, ___) -> cntA2.incrementAndGet());
        assertEquals(0, cntA2.get());

        AtomicInteger cntB2 = new AtomicInteger();
        world.query().withShared(new TeamShared("B")).build().forEachEntity((_, __, ___) -> cntB2.incrementAndGet());
        assertEquals(1, cntB2.get());
    }

    @Test
    void unmanagedShared_basicFilter_parallel() {
        world.registerComponent(TestComponent1.class);
        world.registerComponent(Region.class);

        int v1 = 20, v2 = 22;
        for (int i = 0; i < v1 + v2; i++) {
            int eid = world.createEntity(TestComponent1.class);
            world.setSharedComponent(eid, Region.class, (i < v1) ? 1L : 2L);
        }

        IQuery q1 = world.query().withShared(Region.class, 1L).build();
        AtomicInteger c1 = new AtomicInteger();
        q1.forEachParallel((id, handles, arch) -> c1.incrementAndGet());
        assertEquals(v1, c1.get(), "Unmanaged shared value 1L should match v1 entities");

        IQuery q2 = world.query().withShared(Region.class, 2L).build();
        AtomicInteger c2 = new AtomicInteger();
        q2.forEachParallel((id, handles, arch) -> c2.incrementAndGet());
        assertEquals(v2, c2.get(), "Unmanaged shared value 2L should match v2 entities");
    }

    @Test
    void dynamicAdd_sharedTypes_whenNotInArchetype_initially() {
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TeamShared.class);
        world.registerComponent(Region.class);

        int eid = world.createEntity(TestComponent1.class); // archetype has no shared types yet

        assertDoesNotThrow(() -> world.setSharedComponent(eid, new TeamShared("A")));
        assertDoesNotThrow(() -> world.setSharedComponent(eid, Region.class, 7L));

        AtomicInteger cA = new AtomicInteger();
        world.query().withShared(new TeamShared("A")).build().forEachEntity((_, __, ___) -> cA.incrementAndGet());
        assertEquals(1, cA.get());

        AtomicInteger c7 = new AtomicInteger();
        world.query().withShared(Region.class, 7L).build().forEachEntity((_, __, ___) -> c7.incrementAndGet());
        assertEquals(1, c7.get());
    }
}


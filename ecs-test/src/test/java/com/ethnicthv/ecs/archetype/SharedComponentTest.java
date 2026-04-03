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
    void genericQuery_includes_entities_in_shared_groups() {
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TeamShared.class);

        int sharedA = 7;
        int sharedB = 9;
        for (int i = 0; i < sharedA + sharedB; i++) {
            int eid = world.createEntity(TestComponent1.class);
            world.setSharedComponent(eid, new TeamShared(i < sharedA ? "A" : "B"));
        }

        IQuery query = world.query().with(TestComponent1.class).build();
        AtomicInteger entityCount = new AtomicInteger();
        AtomicInteger chunkEntityCount = new AtomicInteger();

        query.forEachEntity((_, __, ___) -> entityCount.incrementAndGet());
        query.forEachChunk((chunk, __) -> chunkEntityCount.addAndGet(chunk.getEntityCount()));

        assertEquals(sharedA + sharedB, entityCount.get(), "Generic query should include entities from all shared groups");
        assertEquals(sharedA + sharedB, chunkEntityCount.get(), "Chunk traversal should include entities from all shared groups");
        assertEquals(sharedA + sharedB, query.count(), "Query count should include entities from all shared groups");
    }

    @Test
    void sharedQuery_partial_match_still_matches_when_other_shared_types_exist() {
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TeamShared.class);
        world.registerComponent(Region.class);

        int eid = world.createEntity(TestComponent1.class);
        world.setSharedComponent(eid, new TeamShared("A"));
        world.setSharedComponent(eid, Region.class, 7L);

        AtomicInteger teamCount = new AtomicInteger();
        world.query().with(TestComponent1.class).withShared(new TeamShared("A")).build()
                .forEachEntity((_, __, ___) -> teamCount.incrementAndGet());
        assertEquals(1, teamCount.get(), "Managed shared query should match even when other shared filters are unspecified");

        AtomicInteger regionCount = new AtomicInteger();
        world.query().with(TestComponent1.class).withShared(Region.class, 7L).build()
                .forEachEntity((_, __, ___) -> regionCount.incrementAndGet());
        assertEquals(1, regionCount.get(), "Unmanaged shared query should match even when managed shared filters are unspecified");
    }

    @Test
    void sharedQuery_missing_values_return_zero_results_in_all_paths() {
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TeamShared.class);
        world.registerComponent(Region.class);

        int eid = world.createEntity(TestComponent1.class);
        world.setSharedComponent(eid, new TeamShared("A"));
        world.setSharedComponent(eid, Region.class, 7L);

        IQuery missingManaged = world.query().with(TestComponent1.class).withShared(new TeamShared("Missing")).build();
        AtomicInteger missingManagedSequential = new AtomicInteger();
        AtomicInteger missingManagedParallel = new AtomicInteger();
        AtomicInteger missingManagedChunks = new AtomicInteger();
        missingManaged.forEachEntity((_, __, ___) -> missingManagedSequential.incrementAndGet());
        missingManaged.forEachParallel((_, __, ___) -> missingManagedParallel.incrementAndGet());
        missingManaged.forEachChunk((_, __) -> missingManagedChunks.incrementAndGet());
        assertEquals(0, missingManagedSequential.get());
        assertEquals(0, missingManagedParallel.get());
        assertEquals(0, missingManagedChunks.get());
        assertEquals(0, missingManaged.count());

        IQuery missingUnmanaged = world.query().with(TestComponent1.class).withShared(Region.class, 99L).build();
        AtomicInteger missingUnmanagedSequential = new AtomicInteger();
        missingUnmanaged.forEachEntity((_, __, ___) -> missingUnmanagedSequential.incrementAndGet());
        assertEquals(0, missingUnmanagedSequential.get());
        assertEquals(0, missingUnmanaged.count());
    }

    @Test
    void build_returns_snapshot_independent_of_later_builder_mutation() {
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TeamShared.class);

        int entityA = world.createEntity(TestComponent1.class);
        int entityB = world.createEntity(TestComponent1.class);
        world.setSharedComponent(entityA, new TeamShared("A"));
        world.setSharedComponent(entityB, new TeamShared("B"));

        var builder = world.query().with(TestComponent1.class);
        IQuery queryA = builder.withShared(new TeamShared("A")).build();

        builder.withShared(new TeamShared("B"));
        IQuery queryB = builder.build();

        assertEquals(1, queryA.count(), "Built query must keep the shared filter captured at build time");
        assertEquals(1, queryB.count(), "Builder mutation after build should only affect later snapshots");
    }

    @Test
    void build_snapshot_keeps_matching_late_shared_values() {
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TeamShared.class);

        IQuery query = world.query().with(TestComponent1.class).withShared(new TeamShared("Late")).build();
        assertEquals(0, query.count());

        int entity = world.createEntity(TestComponent1.class);
        world.setSharedComponent(entity, new TeamShared("Late"));

        assertEquals(1, query.count(), "Built query should continue to resolve shared values against current world state");
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

//    @Test
//    void dynamicAdd_sharedTypes_whenNotInArchetype_initially() {
//        world.registerComponent(TestComponent1.class);
//        world.registerComponent(TeamShared.class);
//        world.registerComponent(Region.class);
//
//        int eid = world.createEntity(TestComponent1.class); // archetype has no shared types yet
//
//        assertDoesNotThrow(() -> world.setSharedComponent(eid, new TeamShared("A")));
//        assertDoesNotThrow(() -> world.setSharedComponent(eid, Region.class, 7L));
//
//        AtomicInteger cA = new AtomicInteger();
//        world.query().withShared(new TeamShared("A")).build().forEachEntity((_, __, ___) -> cA.incrementAndGet());
//        assertEquals(1, cA.get());
//
//        AtomicInteger c7 = new AtomicInteger();
//        world.query().withShared(Region.class, 7L).build().forEachEntity((_, __, ___) -> c7.incrementAndGet());
//        assertEquals(1, c7.get());
//    }
}


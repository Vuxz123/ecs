package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.api.archetype.IQuery;
import com.ethnicthv.ecs.core.api.archetype.IQueryBuilder;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.SystemManager;
import com.ethnicthv.ecs.core.system.annotation.Id;
import com.ethnicthv.ecs.core.system.annotation.Query;
import com.ethnicthv.ecs.demo.TeamShared;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GeneratedQuerySnapshotTest {

    static class SharedFilterSystem extends BaseSystem {
        IGeneratedQuery q;
        final List<Integer> processed = new ArrayList<>();

        void runCurrent() {
            q.runQuery();
        }

        @Override
        public void onUpdate(float deltaTime) {
            runCurrent();
        }

        @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = {TestComponent1.class})
        private void query(@Id int entityId) {
            processed.add(entityId);
        }
    }

    @Test
    void build_returns_snapshot_and_resets_generated_builder_state() {
        try (ArchetypeWorld world = new ArchetypeWorld(new ComponentManager())) {
            world.registerComponent(TestComponent1.class);
            world.registerComponent(TeamShared.class);

            int entityA = world.createEntity(TestComponent1.class);
            int entityB = world.createEntity(TestComponent1.class);
            world.setSharedComponent(entityA, new TeamShared("A"));
            world.setSharedComponent(entityB, new TeamShared("B"));

            SharedFilterSystem system = new SharedFilterSystem();
            new SystemManager(world).registerSystem(system);

            IQueryBuilder builder = assertInstanceOf(IQueryBuilder.class, system.q);
            IQuery snapshotA = builder.withShared(new TeamShared("A")).build();

            system.processed.clear();
            system.runCurrent();
            assertEquals(Set.of(entityA, entityB), Set.copyOf(system.processed), "Builder state should be reset after build()");

            system.processed.clear();
            snapshotA.runQuery();
            assertEquals(List.of(entityA), system.processed, "Built query should keep the captured shared filter");
        }
    }
}

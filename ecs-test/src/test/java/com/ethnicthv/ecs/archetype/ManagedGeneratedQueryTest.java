package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.SystemManager;
import com.ethnicthv.ecs.core.system.annotation.Id;
import com.ethnicthv.ecs.core.system.annotation.Query;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagedGeneratedQueryTest {

    static class ManagedOnlySystem extends BaseSystem {
        IGeneratedQuery q;
        final Map<Integer, String> seen = new LinkedHashMap<>();

        void runCurrent() {
            q.runQuery();
        }

        @Override
        public void onUpdate(float deltaTime) {
            runCurrent();
        }

        @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = {Profile.class})
        private void query(@Id int entityId, Profile profile) {
            seen.put(entityId, profile.name);
        }
    }

    @Test
    void generated_query_reads_managed_instances_across_add_replace_and_remove() {
        try (ArchetypeWorld world = new ArchetypeWorld(new ComponentManager())) {
            world.registerComponent(Profile.class);

            int entityA = world.createEntity(Profile.class);
            int entityB = world.createEntity();
            int entityC = world.createEntity(Profile.class);
            world.setManagedComponent(entityA, new Profile("A0"));
            world.setManagedComponent(entityC, new Profile("C0"));

            ManagedOnlySystem system = new ManagedOnlySystem();
            new SystemManager(world).registerSystem(system);

            system.runCurrent();
            assertEquals(Map.of(entityA, "A0", entityC, "C0"), Map.copyOf(system.seen));

            world.setManagedComponent(entityA, new Profile("A1"));
            world.addComponent(entityB, new Profile("B0"));
            world.removeComponent(entityC, Profile.class);

            system.seen.clear();
            system.runCurrent();
            assertEquals(Map.of(entityA, "A1", entityB, "B0"), Map.copyOf(system.seen));
        }
    }
}

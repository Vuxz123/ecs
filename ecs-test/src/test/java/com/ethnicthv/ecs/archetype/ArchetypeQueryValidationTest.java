package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.demo.TeamShared;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchetypeQueryValidationTest {

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
    void with_rejects_unregistered_component_immediately() {
        assertThrows(IllegalArgumentException.class, () -> world.query().with(TestComponent1.class));
    }

    @Test
    void with_rejects_managed_component_for_manual_entity_iteration() {
        world.registerComponent(Profile.class);
        assertThrows(IllegalArgumentException.class, () -> world.query().with(Profile.class));
    }

    @Test
    void with_rejects_shared_component_for_manual_entity_iteration() {
        world.registerComponent(TeamShared.class);
        assertThrows(IllegalArgumentException.class, () -> world.query().with(TeamShared.class));
    }

    @Test
    void without_rejects_unregistered_component_immediately() {
        assertThrows(IllegalArgumentException.class, () -> world.query().without(TestComponent1.class));
    }

    @Test
    void any_rejects_unregistered_component_immediately() {
        assertThrows(IllegalArgumentException.class, () -> world.query().any(TestComponent1.class, TestComponent2.class));
    }

    @Test
    void managedSharedFilter_rejects_unregistered_type_immediately() {
        assertThrows(IllegalArgumentException.class, () -> world.query().withShared(new TeamShared("A")));
    }

    @Test
    void unmanagedSharedFilter_rejects_unregistered_type_immediately() {
        assertThrows(IllegalArgumentException.class, () -> world.query().withShared(Region.class, 1L));
    }
}

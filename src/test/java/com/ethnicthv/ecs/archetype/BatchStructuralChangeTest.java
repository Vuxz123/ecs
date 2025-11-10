package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.demo.PositionComponent;
import com.ethnicthv.ecs.demo.VelocityComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BatchStructuralChangeTest {
    static ArchetypeWorld world;

    @BeforeAll
    static void init() {
        ComponentManager cm = new ComponentManager();
        world = new ArchetypeWorld(cm);
        world.registerComponent(PositionComponent.class);
        world.registerComponent(VelocityComponent.class);
    }

    @Test
    void batchAddAndRemoveComponents() {
        int e1 = world.createEntity(PositionComponent.class);
        int e2 = world.createEntity(PositionComponent.class);
        int e3 = world.createEntity(PositionComponent.class);
        ArchetypeWorld.EntityBatch batch = ArchetypeWorld.EntityBatch.of(e1, e2, e3);

        // Batch add Velocity
        world.addComponents(batch, VelocityComponent.class);
        assertTrue(world.hasComponent(e1, VelocityComponent.class));
        assertTrue(world.hasComponent(e2, VelocityComponent.class));
        assertTrue(world.hasComponent(e3, VelocityComponent.class));

        // Batch remove Position
        world.removeComponents(batch, PositionComponent.class);
        assertFalse(world.hasComponent(e1, PositionComponent.class));
        assertFalse(world.hasComponent(e2, PositionComponent.class));
        assertFalse(world.hasComponent(e3, PositionComponent.class));
        assertTrue(world.hasComponent(e1, VelocityComponent.class));
    }
}

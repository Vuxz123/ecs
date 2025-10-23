package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.components.PositionComponent;
import com.ethnicthv.ecs.components.VelocityComponent;
import com.ethnicthv.ecs.core.ComponentManager;
import com.ethnicthv.ecs.core.ComponentHandle;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Demo for ArchetypeQuery.forEachEntityWith that provides pooled ComponentHandles
 */
public class ArchetypeQueryDemo {
    public static void main(String[] args) {
        System.out.println("=== ArchetypeQueryDemo ===\n");

        ComponentManager manager = new ComponentManager();
        ArchetypeWorld world = new ArchetypeWorld(manager);

        // Register components via world
        world.registerComponent(PositionComponent.class);
        world.registerComponent(VelocityComponent.class);

        // Create entities and attach components
        int e1 = world.createEntity();
        int e2 = world.createEntity();
        int e3 = world.createEntity();

        try (Arena arena = Arena.ofConfined()) {
            // e1: pos + vel
            MemorySegment posSeg1 = manager.allocate(PositionComponent.class, arena);
            ComponentHandle ph1 = manager.createHandle(PositionComponent.class, posSeg1);
            ph1.setFloat("x", 1f);
            ph1.setFloat("y", 1.5f);
            manager.releaseHandle(ph1); // release our temporary handle
            MemorySegment velSeg1 = manager.allocate(VelocityComponent.class, arena);
            ComponentHandle vh1 = manager.createHandle(VelocityComponent.class, velSeg1);
            vh1.setFloat("vx", 0.2f);
            vh1.setFloat("vy", -0.1f);
            manager.releaseHandle(vh1);
            world.addComponent(e1, PositionComponent.class, posSeg1);
            world.addComponent(e1, VelocityComponent.class, velSeg1);

            // e2: pos only
            MemorySegment posSeg2 = manager.allocate(PositionComponent.class, arena);
            ComponentHandle ph2 = manager.createHandle(PositionComponent.class, posSeg2);
            ph2.setFloat("x", 5f);
            ph2.setFloat("y", -2f);
            manager.releaseHandle(ph2);
            world.addComponent(e2, PositionComponent.class, posSeg2);

            // e3: pos + vel
            MemorySegment posSeg3 = manager.allocate(PositionComponent.class, arena);
            ComponentHandle ph3 = manager.createHandle(PositionComponent.class, posSeg3);
            ph3.setFloat("x", -3f);
            ph3.setFloat("y", 4f);
            manager.releaseHandle(ph3);
            MemorySegment velSeg3 = manager.allocate(VelocityComponent.class, arena);
            ComponentHandle vh3 = manager.createHandle(VelocityComponent.class, velSeg3);
            vh3.setFloat("vx", 1f);
            vh3.setFloat("vy", 0.5f);
            manager.releaseHandle(vh3);
            world.addComponent(e3, PositionComponent.class, posSeg3);
            world.addComponent(e3, VelocityComponent.class, velSeg3);

            // Query entities that have both Position and Velocity
            System.out.println("Entities with Position+Velocity (using pooled handles):");
            world.query().forEachEntityWith((entityId, handles, location, archetype) -> {
                // handles[0] -> Position, handles[1] -> Velocity
                ComponentHandle ph = handles[0];
                ComponentHandle vh = handles[1];
                float x = ph.getFloat("x");
                float y = ph.getFloat("y");
                float vx = vh.getFloat("vx");
                float vy = vh.getFloat("vy");
                System.out.printf("  entity=%d pos=(%.2f,%.2f) vel=(%.2f,%.2f)\n", entityId, x, y, vx, vy);
            }, PositionComponent.class, VelocityComponent.class);

        }

        world.close();
        System.out.println("\n=== Demo complete ===");
    }
}


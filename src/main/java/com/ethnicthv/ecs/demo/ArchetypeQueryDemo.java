package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.components.ComponentHandle;

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

        // Resolve field indices once (setup-time)
        final int POS_X = manager.getDescriptor(PositionComponent.class).getFieldIndex("x");
        final int POS_Y = manager.getDescriptor(PositionComponent.class).getFieldIndex("y");
        final int VEL_VX = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vx");
        final int VEL_VY = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vy");

        // Create entities and attach components
        int e1 = world.createEntity();
        int e2 = world.createEntity();
        int e3 = world.createEntity();

        try (Arena arena = Arena.ofConfined()) {
            // e1: pos + vel
            MemorySegment posSeg1 = manager.allocate(PositionComponent.class, arena);
            ComponentHandle ph1 = manager.createHandle(PositionComponent.class, posSeg1);
            ph1.setFloat(POS_X, 1f);
            ph1.setFloat(POS_Y, 1.5f);
            manager.releaseHandle(ph1); // release our temporary handle
            MemorySegment velSeg1 = manager.allocate(VelocityComponent.class, arena);
            ComponentHandle vh1 = manager.createHandle(VelocityComponent.class, velSeg1);
            vh1.setFloat(VEL_VX, 0.2f);
            vh1.setFloat(VEL_VY, -0.1f);
            manager.releaseHandle(vh1);
            world.addComponent(e1, PositionComponent.class, posSeg1);
            world.addComponent(e1, VelocityComponent.class, velSeg1);

            // e2: pos only
            MemorySegment posSeg2 = manager.allocate(PositionComponent.class, arena);
            ComponentHandle ph2 = manager.createHandle(PositionComponent.class, posSeg2);
            ph2.setFloat(POS_X, 5f);
            ph2.setFloat(POS_Y, -2f);
            manager.releaseHandle(ph2);
            world.addComponent(e2, PositionComponent.class, posSeg2);

            // e3: pos + vel
            MemorySegment posSeg3 = manager.allocate(PositionComponent.class, arena);
            ComponentHandle ph3 = manager.createHandle(PositionComponent.class, posSeg3);
            ph3.setFloat(POS_X, -3f);
            ph3.setFloat(POS_Y, 4f);
            manager.releaseHandle(ph3);
            MemorySegment velSeg3 = manager.allocate(VelocityComponent.class, arena);
            ComponentHandle vh3 = manager.createHandle(VelocityComponent.class, velSeg3);
            vh3.setFloat(VEL_VX, 1f);
            vh3.setFloat(VEL_VY, 0.5f);
            manager.releaseHandle(vh3);
            world.addComponent(e3, PositionComponent.class, posSeg3);
            world.addComponent(e3, VelocityComponent.class, velSeg3);

            // Query entities that have both Position and Velocity
            System.out.println("Entities with Position+Velocity (using pooled handles):");
            world.query()
                    .with(PositionComponent.class)
                    .with(VelocityComponent.class)
                    .build()
                    .forEachEntity((entityId, handles, archetype) -> {
                        // handles[0] -> Position, handles[1] -> Velocity
                        ComponentHandle ph = handles[0];
                        ComponentHandle vh = handles[1];
                        float x = ph.getFloat(POS_X);
                        float y = ph.getFloat(POS_Y);
                        float vx = vh.getFloat(VEL_VX);
                        float vy = vh.getFloat(VEL_VY);
                        System.out.printf("  entity=%d pos=(%.2f,%.2f) vel=(%.2f,%.2f)\n", entityId, x, y, vx, vy);
                    });

        }

        world.close();
        System.out.println("\n=== Demo complete ===");
    }
}

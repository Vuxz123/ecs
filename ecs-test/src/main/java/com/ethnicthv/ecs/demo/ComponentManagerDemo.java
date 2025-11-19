package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.components.ComponentManager;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Demo showing the new Component system with annotations and ComponentManager
 */
public class ComponentManagerDemo {

    public static void main(String[] args) {
        System.out.println("=== Component Manager Demo ===\n");

        ComponentManager manager = new ComponentManager();

        // Register components
        System.out.println("1. Registering components...");
        int positionId = manager.registerComponent(PositionComponent.class);
        int velocityId = manager.registerComponent(VelocityComponent.class);

        // Resolve field indices once (setup-time)
        final int POS_X = manager.getDescriptor(PositionComponent.class).getFieldIndex("x");
        final int POS_Y = manager.getDescriptor(PositionComponent.class).getFieldIndex("y");
        final int VEL_VX = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vx");
        final int VEL_VY = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vy");

        System.out.println("Position component ID: " + positionId);
        System.out.println("Velocity component ID: " + velocityId);
        System.out.println();

        // Show component descriptors
        System.out.println("2. Component Descriptors:");
        ComponentDescriptor posDesc = manager.getDescriptor(PositionComponent.class);
        System.out.println(posDesc);
        System.out.println();

        ComponentDescriptor velDesc = manager.getDescriptor(VelocityComponent.class);
        System.out.println(velDesc);
        System.out.println();

        // Allocate memory and create handles
        System.out.println("3. Creating component instances with Panama API...");
        try (Arena arena = Arena.ofConfined()) {
            // Allocate position component
            MemorySegment posSegment = manager.allocate(PositionComponent.class, arena);
            ComponentHandle posHandle = manager.createHandle(PositionComponent.class, posSegment);

            // Set position data using handle (index-based)
            posHandle.setFloat(POS_X, 10.5f);
            posHandle.setFloat(POS_Y, 20.3f);

            System.out.println("Position set: x=" + posHandle.getFloat(POS_X) +
                             ", y=" + posHandle.getFloat(POS_Y));

            // Allocate velocity component
            MemorySegment velSegment = manager.allocate(VelocityComponent.class, arena);
            ComponentHandle velHandle = manager.createHandle(VelocityComponent.class, velSegment);

            // Set velocity data using handle (index-based)
            velHandle.setFloat(VEL_VX, 1.5f);
            velHandle.setFloat(VEL_VY, -0.5f);

            System.out.println("Velocity set: vx=" + velHandle.getFloat(VEL_VX) +
                             ", vy=" + velHandle.getFloat(VEL_VY));
            System.out.println();

            // Simulate movement
            System.out.println("4. Simulating movement (10 frames)...");
            for (int i = 0; i < 10; i++) {
                float x = posHandle.getFloat(POS_X);
                float y = posHandle.getFloat(POS_Y);
                float vx = velHandle.getFloat(VEL_VX);
                float vy = velHandle.getFloat(VEL_VY);

                // Update position
                posHandle.setFloat(POS_X, x + vx);
                posHandle.setFloat(POS_Y, y + vy);

                if (i % 3 == 0) {
                    System.out.println("Frame " + i + ": Position(" +
                                     posHandle.getFloat(POS_X) + ", " +
                                     posHandle.getFloat(POS_Y) + ")");
                }
            }

            System.out.println("Final: Position(" +
                             posHandle.getFloat(POS_X) + ", " +
                             posHandle.getFloat(POS_Y) + ")");
            System.out.println();

            // Show memory layout
            System.out.println("5. Memory Layout Information:");
            System.out.println("Position component size: " + posDesc.getTotalSize() + " bytes");
            System.out.println("Velocity component size: " + velDesc.getTotalSize() + " bytes");
            System.out.println("Position segment address: " + posSegment.address());
            System.out.println("Velocity segment address: " + velSegment.address());
        }

        System.out.println("\n=== Demo Complete ===");
    }
}

package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.components.PositionComponent;
import com.ethnicthv.ecs.components.VelocityComponent;
import com.ethnicthv.ecs.core.Component;
import com.ethnicthv.ecs.core.ComponentDescriptor;
import com.ethnicthv.ecs.core.ComponentHandle;
import com.ethnicthv.ecs.core.ComponentManager;

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

            // Set position data using handle
            posHandle.setFloat("x", 10.5f);
            posHandle.setFloat("y", 20.3f);

            System.out.println("Position set: x=" + posHandle.getFloat("x") +
                             ", y=" + posHandle.getFloat("y"));

            // Allocate velocity component
            MemorySegment velSegment = manager.allocate(VelocityComponent.class, arena);
            ComponentHandle velHandle = manager.createHandle(VelocityComponent.class, velSegment);

            // Set velocity data using handle
            velHandle.setFloat("vx", 1.5f);
            velHandle.setFloat("vy", -0.5f);

            System.out.println("Velocity set: vx=" + velHandle.getFloat("vx") +
                             ", vy=" + velHandle.getFloat("vy"));
            System.out.println();

            // Simulate movement
            System.out.println("4. Simulating movement (10 frames)...");
            for (int i = 0; i < 10; i++) {
                float x = posHandle.getFloat("x");
                float y = posHandle.getFloat("y");
                float vx = velHandle.getFloat("vx");
                float vy = velHandle.getFloat("vy");

                // Update position
                posHandle.setFloat("x", x + vx);
                posHandle.setFloat("y", y + vy);

                if (i % 3 == 0) {
                    System.out.println("Frame " + i + ": Position(" +
                                     posHandle.getFloat("x") + ", " +
                                     posHandle.getFloat("y") + ")");
                }
            }

            System.out.println("Final: Position(" +
                             posHandle.getFloat("x") + ", " +
                             posHandle.getFloat("y") + ")");
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


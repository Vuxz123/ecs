package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.api.archetype.IQuery;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Component;
import com.ethnicthv.ecs.core.system.annotation.Query;
import com.ethnicthv.ecs.core.system.SystemManager;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Demo showing the new System API with @Query annotation and automatic parallel execution.
 * <p>
 * This example demonstrates:
 * - Declarative query definition with @Query annotation
 * - Automatic injection by SystemManager
 * - Sequential vs Parallel execution modes
 * - Transparent parallel execution when mode = PARALLEL
 */
public class SystemAPIDemo {

    public static void main(String[] args) {
        // Setup
        ComponentManager componentManager = new ComponentManager();
        ArchetypeWorld world = new ArchetypeWorld(componentManager);
        SystemManager systemManager = new SystemManager(world);

        // Register components
        world.registerComponent(PositionComponent.class);
        world.registerComponent(VelocityComponent.class);
        world.registerComponent(HealthComponent.class);

        // Create entities
        System.out.println("Creating 10,000 entities...");
        for (int i = 0; i < 10000; i++) {
            int entityId = world.createEntity(PositionComponent.class, VelocityComponent.class);

            // Initialize with some data
            MemorySegment velocity = world.getComponent(entityId, VelocityComponent.class);
            if (velocity != null) {
                velocity.set(ValueLayout.JAVA_FLOAT, 0, (float) Math.random() * 10);
                velocity.set(ValueLayout.JAVA_FLOAT, 4, (float) Math.random() * 10);
            }
        }

        // Create and register systems
        MovementSystem movementSystem = new MovementSystem();
        systemManager.registerSystem(movementSystem);

        HealthRegenerationSystem healthSystem = new HealthRegenerationSystem();
        systemManager.registerSystem(healthSystem);

        System.out.println("\n=== Running Systems ===\n");

        // Run movement system (PARALLEL)
        long startTime = System.nanoTime();
        movementSystem.update(); // 60 FPS
        long movementTime = System.nanoTime() - startTime;
        System.out.printf("MovementSystem (PARALLEL): %.2f ms%n", movementTime / 1_000_000.0);

        // Run health system (SEQUENTIAL)
        startTime = System.nanoTime();
        healthSystem.update();
        long healthTime = System.nanoTime() - startTime;
        System.out.printf("HealthRegenerationSystem (SEQUENTIAL): %.2f ms%n", healthTime / 1_000_000.0);

        System.out.println("\n=== Demo Complete ===");

        world.close();
    }

    /**
     * Movement system using PARALLEL execution mode.
     * Processes entities with Position and Velocity components in parallel.
     */
    static class MovementSystem {
        // This query will execute in PARALLEL automatically!
        public IQuery movingEntities;

        void update() {
            // New API: single entrypoint
            movingEntities.runQuery();
        }

        @Query(
                fieldInject = "movingEntities",
                mode = ExecutionMode.SEQUENTIAL, // Explicit but could be omitted (it's default)
                with = HealthComponent.class
        )
        private void query(
                @Component(type = VelocityComponent.class) ComponentHandle velocityHandle,
                @Component(type = PositionComponent.class) ComponentHandle locationHandle
        ) {
            float vx = VelocityComponentAccess.getVx(velocityHandle);
            float vy = VelocityComponentAccess.getVy(velocityHandle);

            float x = PositionComponentAccess.getX(locationHandle);
            float y = PositionComponentAccess.getY(locationHandle);

            x += vx * 0.1f;
            y += vy * 0.1f;

            // Write back
            PositionComponentAccess.setX(locationHandle, x);
            PositionComponentAccess.setY(locationHandle, y);
        }
    }

    /**
     * Health regeneration system using SEQUENTIAL execution.
     * Demonstrates that SEQUENTIAL is still the default and works as expected.
     */
    static class HealthRegenerationSystem {
        private IQuery healthyEntities;

        void update() {
            healthyEntities.runQuery();
        }

        @Query(
                fieldInject = "healthyEntities",
                mode = ExecutionMode.SEQUENTIAL, // Explicit but could be omitted (it's default)
                with = HealthComponent.class
        )
        private void query(
                @Component(type = HealthComponent.class) ComponentHandle healthHandle
        ) {
            int health = HealthComponentAccess.getCurrentHealth(healthHandle);
            int maxHealth = HealthComponentAccess.getMaxHealth(healthHandle);

            if (health < maxHealth) {
                health = Math.min(maxHealth, health + (int) (10 * 0.1f));
                HealthComponentAccess.setCurrentHealth(healthHandle, health);
            }
        }
    }
}

package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.ECS;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.system.SystemManager;

/**
 * Demo showing the new System API with @Query annotation and automatic parallel execution,
 * now wired through the high-level {@link ECS} facade and the pipeline-style SystemManager.
 */
public class SystemAPIDemo {

    public static void main(String[] args) {
        // Setup ECS via facade: this wires ComponentManager + ArchetypeWorld + SystemManager
        try (ECS ecs = ECS.builder()
                // Systems are registered up front; SystemManager will inject queries via AP
                .addSystem(new MovementSystem())
                .addSystem(new HealthRegenerationSystem())
                .registerComponent(NameComponent.class)
                .addSystem(new MixedUnmanagedAndManagedSystem())
                .registerComponent(TeamShared.class)
                .addSystem(new TeamFilterSystem())
                .build()) {

            ArchetypeWorld world = ecs.getWorld();
            SystemManager systemManager = ecs.getSystemManager();

            // Components are auto-registered by GeneratedComponents via ECS.Builder.
            // Create entities using the new ECS API
            System.out.println("Creating 10,000 entities...");
            for (int i = 0; i < 10000; i++) {
                int entityId = ecs.createEntity(
                        PositionComponent.class,
                        VelocityComponent.class,
                        HealthComponent.class,
                        NameComponent.class,
                        IndexComponent.class
                );

                int indexValue = i; // effectively final copy for lambda capture

                // Initialize unmanaged components via typed-handle API (zero-copy)
                ecs.addComponent(entityId, VelocityComponent.class, (VelocityComponentHandle v) -> {
                    v.setVx((float) Math.random() * 10);
                    v.setVy((float) Math.random() * 10);
                });

                ecs.addComponent(entityId, PositionComponent.class, (PositionComponentHandle p) -> {
                    p.setX((float) Math.random() * 100);
                    p.setY((float) Math.random() * 100);
                });

                ecs.addComponent(entityId, HealthComponent.class, (HealthComponentHandle h) -> {
                    h.setCurrentHealth(50);
                    h.setMaxHealth(100);
                });

                ecs.addComponent(entityId, IndexComponent.class, (IndexComponentHandle idx) -> {
                    idx.setIndex(indexValue);
                });

                // Managed + shared components are still set via ArchetypeWorld helpers
                NameComponent nameComp = new NameComponent();
                nameComp.name = "Entity_" + entityId;
                world.setManagedComponent(entityId, nameComp);

                TeamShared team = (i % 2 == 0) ? new TeamShared("A") : new TeamShared("B");
                world.setSharedComponent(entityId, team);
            }

            System.out.println("\n=== Running Systems via SystemManager.update() ===\n");

            // Single pipeline call that runs all enabled systems in group-priority order
            long startTime = System.nanoTime();
            float deltaTime = 0.016f; // pretend ~60 FPS fixed step for the demo
            systemManager.update(deltaTime);
            long totalTime = System.nanoTime() - startTime;
            System.out.printf("System pipeline update: %.2f ms%n", totalTime / 1_000_000.0);

            System.out.println("\n=== Demo Complete ===");
        }
    }

}
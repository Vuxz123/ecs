package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.api.archetype.IQuery;
import com.ethnicthv.ecs.core.api.archetype.IQueryBuilder;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Component;
import com.ethnicthv.ecs.core.system.annotation.Id;
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
        world.registerComponent(NameComponent.class);
        world.registerComponent(TeamShared.class);
        world.registerComponent(IndexComponent.class);

        // Create entities
        System.out.println("Creating 10,000 entities...");
        for (int i = 0; i < 10000; i++) {
            int entityId = world.createEntity(
                    PositionComponent.class,
                    VelocityComponent.class,
                    HealthComponent.class,
                    NameComponent.class,
                    IndexComponent.class
            );

            // Initialize with some data
            MemorySegment velocity = world.getComponent(entityId, VelocityComponent.class);
            if (velocity != null) {
                velocity.set(ValueLayout.JAVA_FLOAT, 0, (float) Math.random() * 10);
                velocity.set(ValueLayout.JAVA_FLOAT, 4, (float) Math.random() * 10);
            }

            MemorySegment position = world.getComponent(entityId, PositionComponent.class);
            if (position != null) {
                position.set(ValueLayout.JAVA_FLOAT, 0, (float) Math.random() * 100);
                position.set(ValueLayout.JAVA_FLOAT, 4, (float) Math.random() * 100);
            }

            MemorySegment health = world.getComponent(entityId, HealthComponent.class);
            if (health != null) {
                health.set(ValueLayout.JAVA_INT, 0, 50); // current health
                health.set(ValueLayout.JAVA_INT, 4, 100); // max health
            }

            MemorySegment indexComp = world.getComponent(entityId, IndexComponent.class);
            if (indexComp != null) {
                indexComp.set(ValueLayout.JAVA_INT, 0, i);
            }

            NameComponent nameComp = new NameComponent();
            nameComp.name = "Entity_" + entityId;
            world.setManagedComponent(entityId, nameComp);

            // Assign shared team value (e.g., A/B) to create chunk groups
            TeamShared team = (i % 2 == 0) ? new TeamShared("A") : new TeamShared("B");
            world.setSharedComponent(entityId, team);
        }

        // Create and register systems
        MovementSystem movementSystem = new MovementSystem();
        systemManager.registerSystem(movementSystem);

        HealthRegenerationSystem healthSystem = new HealthRegenerationSystem();
        systemManager.registerSystem(healthSystem);

        MixedUnmanagedAndManagedSystem mixedSystem = new MixedUnmanagedAndManagedSystem();
        systemManager.registerSystem(mixedSystem);

        TeamFilterSystem teamFilterSystem = new TeamFilterSystem();
        systemManager.registerSystem(teamFilterSystem);

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

        // Run mixed system (PARALLEL)
        startTime = System.nanoTime();
        mixedSystem.update();
        long mixedTime = System.nanoTime() - startTime;
        System.out.printf("MixedUnmanagedAndManagedSystem (PARALLEL): %.2f ms%n", mixedTime / 1_000_000.0);

        // Run team filter system (SEQUENTIAL, Team A only)
        startTime = System.nanoTime();
        teamFilterSystem.updateOnlyTeamA();
        long teamTime = System.nanoTime() - startTime;
        System.out.printf("TeamFilterSystem (SEQUENTIAL, Team A only): %.2f ms%n", teamTime / 1_000_000.0);

        System.out.println("\n=== Demo Complete ===");

        world.close();
    }

    /**
     * Movement system using PARALLEL execution mode.
     * Processes entities with Position and Velocity components in parallel.
     */
    static class MovementSystem {
        // This query will execute in PARALLEL automatically!
        public IGeneratedQuery movingEntities;

        private int firstEntityId = Integer.MAX_VALUE;

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
                @Id Integer entityId,
                @Component(type = VelocityComponent.class) VelocityComponentHandle velocityHandle,
                @Component(type = PositionComponent.class) ComponentHandle locationHandle
        ) {

            float vx = velocityHandle.getVx();
            float vy = velocityHandle.getVy();

            float x = PositionComponentAccess.getX(locationHandle);
            float y = PositionComponentAccess.getY(locationHandle);

            x += vx * 0.1f;
            y += vy * 0.1f;

            // Write back
            PositionComponentAccess.setX(locationHandle, x);
            PositionComponentAccess.setY(locationHandle, y);

            if (firstEntityId == Integer.MAX_VALUE) {
                System.out.println("Logging movement of first entity only for demo purposes...");
                firstEntityId = entityId;
            }
            else if (entityId != firstEntityId) {
                return;
            }

            System.out.println("Moved Entity " + entityId + " to (" + x + ", " + y + ")");
            System.out.println("First Entity ID: " + firstEntityId);
        }
    }

    /**
     * Health regeneration system using SEQUENTIAL execution.
     * Demonstrates that SEQUENTIAL is still the default and works as expected.
     */
    static class HealthRegenerationSystem {
        private IGeneratedQuery healthyEntities;

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

    static class MixedUnmanagedAndManagedSystem {
        private IGeneratedQuery mixedEntities;

        void update() {
            mixedEntities.runQuery();
        }

        @Query(
                fieldInject = "mixedEntities",
                mode = ExecutionMode.PARALLEL,
                with = { PositionComponent.class, HealthComponent.class }
        )
        private void query(
                @Component(type = PositionComponent.class) ComponentHandle positionHandle,
                NameComponent nameComponent
        ) {
            // Update position
            float x = PositionComponentAccess.getX(positionHandle);
            float y = PositionComponentAccess.getY(positionHandle);
            x += 1.0f;
            y += 1.0f;
            PositionComponentAccess.setX(positionHandle, x);
            PositionComponentAccess.setY(positionHandle, y);

            // log name from managed component
            //System.out.println("Entity Name: " + nameComponent.name);
        }
    }

    static class TeamFilterSystem {
        private IGeneratedQuery q;

        void updateOnlyTeamA() {
            // The generated runner will keep stateful filter and reset after run
            if (q instanceof IQueryBuilder b) {
                b.withShared(new TeamShared("A")).build().runQuery();
            } else if (q != null) {
                System.out.println("Warning: Query instance is not a builder, cannot set shared filter dynamically.");
                q.runQuery();
            }
        }

        @Query(
                fieldInject = "q",
                mode = ExecutionMode.SEQUENTIAL,
                with = { PositionComponent.class, VelocityComponent.class }
        )
        private void query(
                @Id int entityId,
                @Component(type = PositionComponent.class) ComponentHandle pos,
                @Component(type = VelocityComponent.class) VelocityComponentHandle vel,
                @Component(type = IndexComponent.class) IndexComponentHandle index
        ) {
            float x = PositionComponentAccess.getX(pos);
            float y = PositionComponentAccess.getY(pos);
            x += vel.getVx() * 0.05f;
            y += vel.getVy() * 0.05f;
            PositionComponentAccess.setX(pos, x);
            PositionComponentAccess.setY(pos, y);

            if (index.getIndex() % 2 == 1) {
                System.out.println("Team A Entity " + entityId + "with Index " + index.getIndex() + " moved to (" + x + ", " + y + ")");
            }

        }
    }
}
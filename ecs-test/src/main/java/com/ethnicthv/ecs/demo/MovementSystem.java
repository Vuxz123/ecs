package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Component;
import com.ethnicthv.ecs.core.system.annotation.Id;
import com.ethnicthv.ecs.core.system.annotation.Query;

/**
 * Movement system using PARALLEL execution mode.
 * Processes entities with Position and Velocity components in parallel.
 */
class MovementSystem extends BaseSystem {
    // This query will execute in PARALLEL automatically!
    public IGeneratedQuery movingEntities;

    private int firstEntityId = Integer.MAX_VALUE;

    @Override
    public void onUpdate(float deltaTime) {
        // New API: single entrypoint
        if (movingEntities != null) {
            movingEntities.runQuery();
        }
    }

    @Query(
            fieldInject = "movingEntities",
            mode = ExecutionMode.SEQUENTIAL, // Explicit but could be omitted (it's default)
            with = HealthComponent.class
    )
    private void query(
            @Id Integer entityId,
            @Component(type = VelocityComponent.class) VelocityComponentHandle velocityHandle,
            @Component(type = PositionComponent.class) PositionComponentHandle locationHandle
    ) {

        float vx = velocityHandle.getVx();
        float vy = velocityHandle.getVy();

        float x = locationHandle.getX();
        float y = locationHandle.getY();

        x += vx * 0.1f;
        y += vy * 0.1f;

        // Write back
        locationHandle.setX(x);
        locationHandle.setY(y);

        if (firstEntityId == Integer.MAX_VALUE) {
            System.out.println("Logging movement of first entity only for demo purposes...");
            firstEntityId = entityId;
        } else if (entityId != firstEntityId) {
            return;
        }

        System.out.println("Moved Entity " + entityId + " to (" + x + ", " + y + ")");
        System.out.println("First Entity ID: " + firstEntityId);
    }
}

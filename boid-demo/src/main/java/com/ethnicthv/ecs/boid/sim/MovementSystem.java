package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Component;
import com.ethnicthv.ecs.core.system.annotation.Id;
import com.ethnicthv.ecs.core.system.annotation.Query;

public final class MovementSystem extends BaseSystem {
    private final float worldHalfExtent;
    private final float minSpeed;
    private final float maxSpeed;
    private final SpatialHashGrid spatialHash;

    private IGeneratedQuery movementQuery;
    private float deltaTime;

    public MovementSystem(float worldHalfExtent, float minSpeed, float maxSpeed, SpatialHashGrid spatialHash) {
        this.worldHalfExtent = worldHalfExtent;
        this.minSpeed = minSpeed;
        this.maxSpeed = maxSpeed;
        this.spatialHash = spatialHash;
    }

    @Override
    public void onUpdate(float deltaTime) {
        this.deltaTime = deltaTime;
        if (movementQuery != null) {
            movementQuery.runQuery();
        }
    }

    @Query(
        fieldInject = "movementQuery",
        mode = ExecutionMode.SEQUENTIAL,
        with = {Position3.class, Velocity3.class, Acceleration3.class}
    )
    private void query(
        @Id int entityId,
        @Component(type = Position3.class) Position3Handle position,
        @Component(type = Velocity3.class) Velocity3Handle velocity,
        @Component(type = Acceleration3.class) Acceleration3Handle acceleration
    ) {
        float vx = velocity.getX() + acceleration.getX() * deltaTime;
        float vy = velocity.getY() + acceleration.getY() * deltaTime;
        float vz = velocity.getZ() + acceleration.getZ() * deltaTime;

        float speedSq = vx * vx + vy * vy + vz * vz;
        float minSpeedSq = minSpeed * minSpeed;
        float maxSpeedSq = maxSpeed * maxSpeed;
        if (speedSq > maxSpeedSq) {
            float scale = maxSpeed / (float) Math.sqrt(speedSq);
            vx *= scale;
            vy *= scale;
            vz *= scale;
        } else if (speedSq > 0.0001f && speedSq < minSpeedSq) {
            float scale = minSpeed / (float) Math.sqrt(speedSq);
            vx *= scale;
            vy *= scale;
            vz *= scale;
        }

        float x = wrap(position.getX() + vx * deltaTime, worldHalfExtent);
        float y = wrap(position.getY() + vy * deltaTime, worldHalfExtent);
        float z = wrap(position.getZ() + vz * deltaTime, worldHalfExtent);

        velocity.setX(vx);
        velocity.setY(vy);
        velocity.setZ(vz);

        position.setX(x);
        position.setY(y);
        position.setZ(z);

        spatialHash.publishRenderPosition(entityId, x, y, z);
    }

    private static float wrap(float value, float halfExtent) {
        if (value > halfExtent) {
            return -halfExtent;
        }
        if (value < -halfExtent) {
            return halfExtent;
        }
        return value;
    }
}

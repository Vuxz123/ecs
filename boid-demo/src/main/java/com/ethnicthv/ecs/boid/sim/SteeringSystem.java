package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Component;
import com.ethnicthv.ecs.core.system.annotation.Id;
import com.ethnicthv.ecs.core.system.annotation.Query;

public final class SteeringSystem extends BaseSystem {
    private static final float EPSILON = 0.0001f;

    private final SpatialHashGrid spatialHash;
    private final float maxSpeed;
    private final float separationRadius;
    private final float inverseSeparationRadius;
    private final float neighborRadiusSq;
    private final float separationRadiusSq;
    private final boolean fastMathEnabled;
    private float maxForce;
    private float maxForceSq;
    private float separationWeight;
    private float alignmentWeight;
    private float cohesionWeight;

    private IGeneratedQuery sequentialSteeringQuery;
    private IGeneratedQuery parallelSteeringQuery;
    private SteeringExecutionMode executionMode;
    private long lastUpdateNanos;
    private long totalUpdateNanos;
    private long updateCount;

    public SteeringSystem(SpatialHashGrid spatialHash, SimulationConfig config) {
        this.spatialHash = spatialHash;
        this.maxSpeed = config.maxSpeed();
        this.maxForce = config.maxForce();
        this.maxForceSq = maxForce * maxForce;
        this.separationRadius = config.separationRadius();
        this.inverseSeparationRadius = 1.0f / config.separationRadius();
        this.neighborRadiusSq = config.neighborRadius() * config.neighborRadius();
        this.separationRadiusSq = config.separationRadius() * config.separationRadius();
        this.fastMathEnabled = config.fastMath();
        this.separationWeight = config.separationWeight();
        this.alignmentWeight = config.alignmentWeight();
        this.cohesionWeight = config.cohesionWeight();
        this.executionMode = config.steeringExecutionMode();
    }

    @Override
    public void onUpdate(float deltaTime) {
        IGeneratedQuery steeringQuery = executionMode == SteeringExecutionMode.PARALLEL
            ? parallelSteeringQuery
            : sequentialSteeringQuery;
        if (steeringQuery == null) {
            return;
        }

        long startedAt = System.nanoTime();
        steeringQuery.runQuery();
        lastUpdateNanos = System.nanoTime() - startedAt;
        totalUpdateNanos += lastUpdateNanos;
        updateCount++;
    }

    @Query(
        fieldInject = "sequentialSteeringQuery",
        mode = ExecutionMode.SEQUENTIAL,
        with = {Acceleration3.class, NeighborBuffer.class}
    )
    private void querySequential(
        @Id int entityId,
        @Component(type = Acceleration3.class) Acceleration3Handle acceleration,
        @Component(type = NeighborBuffer.class) NeighborBufferHandle neighborBuffer
    ) {
        computeSteering(entityId, acceleration, neighborBuffer);
    }

    @Query(
        fieldInject = "parallelSteeringQuery",
        mode = ExecutionMode.PARALLEL,
        with = {Acceleration3.class, NeighborBuffer.class}
    )
    private void queryParallel(
        @Id int entityId,
        @Component(type = Acceleration3.class) Acceleration3Handle acceleration,
        @Component(type = NeighborBuffer.class) NeighborBufferHandle neighborBuffer
    ) {
        computeSteering(entityId, acceleration, neighborBuffer);
    }

    public SteeringExecutionMode executionMode() {
        return executionMode;
    }

    public void setExecutionMode(SteeringExecutionMode executionMode) {
        if (executionMode == null) {
            throw new IllegalArgumentException("executionMode must not be null");
        }
        this.executionMode = executionMode;
    }

    public long lastUpdateNanos() {
        return lastUpdateNanos;
    }

    public long averageUpdateNanos() {
        return updateCount == 0L ? 0L : totalUpdateNanos / updateCount;
    }

    public float maxForce() {
        return maxForce;
    }

    public void setMaxForce(float maxForce) {
        if (maxForce <= 0.0f) {
            throw new IllegalArgumentException("maxForce must be > 0");
        }
        this.maxForce = maxForce;
        this.maxForceSq = maxForce * maxForce;
    }

    public float separationWeight() {
        return separationWeight;
    }

    public void setSeparationWeight(float separationWeight) {
        if (separationWeight < 0.0f) {
            throw new IllegalArgumentException("separationWeight must be >= 0");
        }
        this.separationWeight = separationWeight;
    }

    public float alignmentWeight() {
        return alignmentWeight;
    }

    public void setAlignmentWeight(float alignmentWeight) {
        if (alignmentWeight < 0.0f) {
            throw new IllegalArgumentException("alignmentWeight must be >= 0");
        }
        this.alignmentWeight = alignmentWeight;
    }

    public float cohesionWeight() {
        return cohesionWeight;
    }

    public void setCohesionWeight(float cohesionWeight) {
        if (cohesionWeight < 0.0f) {
            throw new IllegalArgumentException("cohesionWeight must be >= 0");
        }
        this.cohesionWeight = cohesionWeight;
    }

    private void computeSteering(int entityId, Acceleration3Handle acceleration, NeighborBufferHandle neighborBuffer) {
        int boidIndex = spatialHash.boidIndexOf(entityId);
        if (boidIndex < 0) {
            throw new IllegalStateException("Unknown boid entity during steering: " + entityId);
        }

        float px = spatialHash.positionX(boidIndex);
        float py = spatialHash.positionY(boidIndex);
        float pz = spatialHash.positionZ(boidIndex);
        float vx = spatialHash.velocityX(boidIndex);
        float vy = spatialHash.velocityY(boidIndex);
        float vz = spatialHash.velocityZ(boidIndex);

        float separationX = 0.0f;
        float separationY = 0.0f;
        float separationZ = 0.0f;
        float alignmentX = 0.0f;
        float alignmentY = 0.0f;
        float alignmentZ = 0.0f;
        float cohesionX = 0.0f;
        float cohesionY = 0.0f;
        float cohesionZ = 0.0f;
        int separationCount = 0;
        int neighborCount = neighborBuffer.getCount();
        float maxForce = this.maxForce;
        float maxForceSq = this.maxForceSq;
        for (int neighborSlot = 0; neighborSlot < neighborCount; neighborSlot++) {
            int neighborIndex = neighborBuffer.getNeighbors(neighborSlot);
            byte offsetPack = neighborBuffer.getOffsetPacks(neighborSlot);
            float deltaX = spatialHash.positionX(neighborIndex) + spatialHash.offsetXFromPack(offsetPack) - px;
            float deltaY = spatialHash.positionY(neighborIndex) + spatialHash.offsetYFromPack(offsetPack) - py;
            float deltaZ = spatialHash.positionZ(neighborIndex) + spatialHash.offsetZFromPack(offsetPack) - pz;
            float distanceSq = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
            if (distanceSq <= EPSILON || distanceSq > neighborRadiusSq) {
                continue;
            }

            alignmentX += spatialHash.velocityX(neighborIndex);
            alignmentY += spatialHash.velocityY(neighborIndex);
            alignmentZ += spatialHash.velocityZ(neighborIndex);
            cohesionX += deltaX;
            cohesionY += deltaY;
            cohesionZ += deltaZ;

            if (distanceSq <= separationRadiusSq) {
                float invDistance = inverseSqrt(distanceSq, fastMathEnabled);
                float distance = distanceSq * invDistance;
                float influence = (separationRadius - distance) * inverseSeparationRadius;
                separationX -= deltaX * invDistance * influence;
                separationY -= deltaY * invDistance * influence;
                separationZ -= deltaZ * invDistance * influence;
                separationCount++;
            }
        }

        float steeringX = 0.0f;
        float steeringY = 0.0f;
        float steeringZ = 0.0f;

        if (separationCount > 0 && separationWeight > 0.0f) {
            float invCount = 1.0f / separationCount;
            float desiredX = separationX * invCount;
            float desiredY = separationY * invCount;
            float desiredZ = separationZ * invCount;
            float desiredLengthSq = desiredX * desiredX + desiredY * desiredY + desiredZ * desiredZ;
            if (desiredLengthSq > EPSILON) {
                float desiredScale = maxSpeed * inverseSqrt(desiredLengthSq, fastMathEnabled);
                float steerX = desiredX * desiredScale - vx;
                float steerY = desiredY * desiredScale - vy;
                float steerZ = desiredZ * desiredScale - vz;
                float steerScale = clampScale(steerX, steerY, steerZ, maxForce, maxForceSq, fastMathEnabled);
                steeringX += steerX * steerScale * separationWeight;
                steeringY += steerY * steerScale * separationWeight;
                steeringZ += steerZ * steerScale * separationWeight;
            }
        }

        if (neighborCount > 0) {
            float invCount = 1.0f / neighborCount;
            if (alignmentWeight > 0.0f) {
                float desiredX = alignmentX * invCount;
                float desiredY = alignmentY * invCount;
                float desiredZ = alignmentZ * invCount;
                float desiredLengthSq = desiredX * desiredX + desiredY * desiredY + desiredZ * desiredZ;
                if (desiredLengthSq > EPSILON) {
                    float desiredScale = maxSpeed * inverseSqrt(desiredLengthSq, fastMathEnabled);
                    float steerX = desiredX * desiredScale - vx;
                    float steerY = desiredY * desiredScale - vy;
                    float steerZ = desiredZ * desiredScale - vz;
                    float steerScale = clampScale(steerX, steerY, steerZ, maxForce, maxForceSq, fastMathEnabled);
                    steeringX += steerX * steerScale * alignmentWeight;
                    steeringY += steerY * steerScale * alignmentWeight;
                    steeringZ += steerZ * steerScale * alignmentWeight;
                }
            }

            if (cohesionWeight > 0.0f) {
                float desiredX = cohesionX * invCount;
                float desiredY = cohesionY * invCount;
                float desiredZ = cohesionZ * invCount;
                float desiredLengthSq = desiredX * desiredX + desiredY * desiredY + desiredZ * desiredZ;
                if (desiredLengthSq > EPSILON) {
                    float desiredScale = maxSpeed * inverseSqrt(desiredLengthSq, fastMathEnabled);
                    float steerX = desiredX * desiredScale - vx;
                    float steerY = desiredY * desiredScale - vy;
                    float steerZ = desiredZ * desiredScale - vz;
                    float steerScale = clampScale(steerX, steerY, steerZ, maxForce, maxForceSq, fastMathEnabled);
                    steeringX += steerX * steerScale * cohesionWeight;
                    steeringY += steerY * steerScale * cohesionWeight;
                    steeringZ += steerZ * steerScale * cohesionWeight;
                }
            }
        }

        float steeringScale = clampScale(steeringX, steeringY, steeringZ, maxForce, maxForceSq, fastMathEnabled);
        acceleration.setX(steeringX * steeringScale);
        acceleration.setY(steeringY * steeringScale);
        acceleration.setZ(steeringZ * steeringScale);
    }

    private static float clampScale(float x, float y, float z, float maxMagnitude, float maxMagnitudeSq, boolean fastMathEnabled) {
        float magnitudeSq = x * x + y * y + z * z;
        if (magnitudeSq <= EPSILON || magnitudeSq <= maxMagnitudeSq) {
            return 1.0f;
        }
        return maxMagnitude * inverseSqrt(magnitudeSq, fastMathEnabled);
    }

    private static float inverseSqrt(float value, boolean fastMathEnabled) {
        if (!fastMathEnabled) {
            return 1.0f / (float) Math.sqrt(value);
        }
        return fastInverseSqrt(value);
    }

    private static float fastInverseSqrt(float value) {
        float half = 0.5f * value;
        int bits = Float.floatToIntBits(value);
        bits = 0x5f3759df - (bits >> 1);
        float estimate = Float.intBitsToFloat(bits);
        return estimate * (1.5f - half * estimate * estimate);
    }
}

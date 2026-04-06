package com.ethnicthv.ecs.boid.sim;

public record SimulationConfig(
    int width,
    int height,
    String title,
    float backgroundRed,
    float backgroundGreen,
    float backgroundBlue,
    int initialBoidCount,
    float fixedTickRate,
    float spawnExtent,
    float worldHalfExtent,
    float minSpeed,
    float maxSpeed,
    float maxForce,
    float neighborRadius,
    float separationRadius,
    float separationWeight,
    float alignmentWeight,
    float cohesionWeight,
    int maxNeighbors,
    int targetSamplesPerCell,
    int neighborRefreshIntervalTicks,
    boolean fastMath,
    SteeringExecutionMode steeringExecutionMode,
    long randomSeed
) {
    public SimulationConfig {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (initialBoidCount < 0) {
            throw new IllegalArgumentException("initialBoidCount must be >= 0");
        }
        if (fixedTickRate <= 0.0f) {
            throw new IllegalArgumentException("fixedTickRate must be > 0");
        }
        if (spawnExtent <= 0.0f) {
            throw new IllegalArgumentException("spawnExtent must be > 0");
        }
        if (worldHalfExtent <= 0.0f) {
            throw new IllegalArgumentException("worldHalfExtent must be > 0");
        }
        if (minSpeed < 0.0f) {
            throw new IllegalArgumentException("minSpeed must be >= 0");
        }
        if (maxSpeed <= 0.0f || maxSpeed < minSpeed) {
            throw new IllegalArgumentException("maxSpeed must be >= minSpeed and > 0");
        }
        if (maxForce <= 0.0f) {
            throw new IllegalArgumentException("maxForce must be > 0");
        }
        if (neighborRadius <= 0.0f) {
            throw new IllegalArgumentException("neighborRadius must be > 0");
        }
        if (separationRadius <= 0.0f || separationRadius > neighborRadius) {
            throw new IllegalArgumentException("separationRadius must be > 0 and <= neighborRadius");
        }
        if (separationWeight < 0.0f || alignmentWeight < 0.0f || cohesionWeight < 0.0f) {
            throw new IllegalArgumentException("flocking weights must be >= 0");
        }
        if (maxNeighbors <= 0) {
            throw new IllegalArgumentException("maxNeighbors must be > 0");
        }
        if (maxNeighbors > NeighborBuffer.CAPACITY) {
            throw new IllegalArgumentException(
                "maxNeighbors must be <= NeighborBuffer.CAPACITY (" + NeighborBuffer.CAPACITY + ")"
            );
        }
        if (targetSamplesPerCell <= 0) {
            throw new IllegalArgumentException("targetSamplesPerCell must be > 0");
        }
        if (neighborRefreshIntervalTicks <= 0) {
            throw new IllegalArgumentException("neighborRefreshIntervalTicks must be > 0");
        }
        if (steeringExecutionMode == null) {
            throw new IllegalArgumentException("steeringExecutionMode must not be null");
        }
    }

    public static SimulationConfig defaultConfig() {
        return new SimulationConfig(
            1280,
            720,
            "ECS Boid Demo - Phase 2",
            0.06f,
            0.08f,
            0.12f,
            25_000,
            60.0f,
            75.0f,
            150.0f,
            4.0f,
            18.0f,
            14.0f,
            12.0f,
            5.0f,
            1.8f,
            0.8f,
            0.6f,
            16,
            16,
            2,
            false,
            SteeringExecutionMode.SEQUENTIAL,
            0xB01DL
        );
    }

    public SimulationConfig withInitialBoidCount(int newInitialBoidCount) {
        return new SimulationConfig(
            width,
            height,
            title,
            backgroundRed,
            backgroundGreen,
            backgroundBlue,
            newInitialBoidCount,
            fixedTickRate,
            spawnExtent,
            worldHalfExtent,
            minSpeed,
            maxSpeed,
            maxForce,
            neighborRadius,
            separationRadius,
            separationWeight,
            alignmentWeight,
            cohesionWeight,
            maxNeighbors,
            targetSamplesPerCell,
            neighborRefreshIntervalTicks,
            fastMath,
            steeringExecutionMode,
            randomSeed
        );
    }

    public SimulationConfig withRandomSeed(long newRandomSeed) {
        return new SimulationConfig(
            width,
            height,
            title,
            backgroundRed,
            backgroundGreen,
            backgroundBlue,
            initialBoidCount,
            fixedTickRate,
            spawnExtent,
            worldHalfExtent,
            minSpeed,
            maxSpeed,
            maxForce,
            neighborRadius,
            separationRadius,
            separationWeight,
            alignmentWeight,
            cohesionWeight,
            maxNeighbors,
            targetSamplesPerCell,
            neighborRefreshIntervalTicks,
            fastMath,
            steeringExecutionMode,
            newRandomSeed
        );
    }

    public SimulationConfig withSpeedRange(float newMinSpeed, float newMaxSpeed) {
        return new SimulationConfig(
            width,
            height,
            title,
            backgroundRed,
            backgroundGreen,
            backgroundBlue,
            initialBoidCount,
            fixedTickRate,
            spawnExtent,
            worldHalfExtent,
            newMinSpeed,
            newMaxSpeed,
            maxForce,
            neighborRadius,
            separationRadius,
            separationWeight,
            alignmentWeight,
            cohesionWeight,
            maxNeighbors,
            targetSamplesPerCell,
            neighborRefreshIntervalTicks,
            fastMath,
            steeringExecutionMode,
            randomSeed
        );
    }

    public SimulationConfig withWorldBounds(float newSpawnExtent, float newWorldHalfExtent) {
        return new SimulationConfig(
            width,
            height,
            title,
            backgroundRed,
            backgroundGreen,
            backgroundBlue,
            initialBoidCount,
            fixedTickRate,
            newSpawnExtent,
            newWorldHalfExtent,
            minSpeed,
            maxSpeed,
            maxForce,
            neighborRadius,
            separationRadius,
            separationWeight,
            alignmentWeight,
            cohesionWeight,
            maxNeighbors,
            targetSamplesPerCell,
            neighborRefreshIntervalTicks,
            fastMath,
            steeringExecutionMode,
            randomSeed
        );
    }

    public SimulationConfig withFlocking(
        float newNeighborRadius,
        float newSeparationRadius,
        float newSeparationWeight,
        float newAlignmentWeight,
        float newCohesionWeight,
        float newMaxForce
    ) {
        return new SimulationConfig(
            width,
            height,
            title,
            backgroundRed,
            backgroundGreen,
            backgroundBlue,
            initialBoidCount,
            fixedTickRate,
            spawnExtent,
            worldHalfExtent,
            minSpeed,
            maxSpeed,
            newMaxForce,
            newNeighborRadius,
            newSeparationRadius,
            newSeparationWeight,
            newAlignmentWeight,
            newCohesionWeight,
            maxNeighbors,
            targetSamplesPerCell,
            neighborRefreshIntervalTicks,
            fastMath,
            steeringExecutionMode,
            randomSeed
        );
    }

    public SimulationConfig withMaxNeighbors(int newMaxNeighbors) {
        return new SimulationConfig(
            width,
            height,
            title,
            backgroundRed,
            backgroundGreen,
            backgroundBlue,
            initialBoidCount,
            fixedTickRate,
            spawnExtent,
            worldHalfExtent,
            minSpeed,
            maxSpeed,
            maxForce,
            neighborRadius,
            separationRadius,
            separationWeight,
            alignmentWeight,
            cohesionWeight,
            newMaxNeighbors,
            targetSamplesPerCell,
            neighborRefreshIntervalTicks,
            fastMath,
            steeringExecutionMode,
            randomSeed
        );
    }

    public SimulationConfig withTargetSamplesPerCell(int newTargetSamplesPerCell) {
        return new SimulationConfig(
            width,
            height,
            title,
            backgroundRed,
            backgroundGreen,
            backgroundBlue,
            initialBoidCount,
            fixedTickRate,
            spawnExtent,
            worldHalfExtent,
            minSpeed,
            maxSpeed,
            maxForce,
            neighborRadius,
            separationRadius,
            separationWeight,
            alignmentWeight,
            cohesionWeight,
            maxNeighbors,
            newTargetSamplesPerCell,
            neighborRefreshIntervalTicks,
            fastMath,
            steeringExecutionMode,
            randomSeed
        );
    }

    public SimulationConfig withNeighborRefreshIntervalTicks(int newNeighborRefreshIntervalTicks) {
        return new SimulationConfig(
            width,
            height,
            title,
            backgroundRed,
            backgroundGreen,
            backgroundBlue,
            initialBoidCount,
            fixedTickRate,
            spawnExtent,
            worldHalfExtent,
            minSpeed,
            maxSpeed,
            maxForce,
            neighborRadius,
            separationRadius,
            separationWeight,
            alignmentWeight,
            cohesionWeight,
            maxNeighbors,
            targetSamplesPerCell,
            newNeighborRefreshIntervalTicks,
            fastMath,
            steeringExecutionMode,
            randomSeed
        );
    }

    public SimulationConfig withFastMath(boolean newFastMath) {
        return new SimulationConfig(
            width,
            height,
            title,
            backgroundRed,
            backgroundGreen,
            backgroundBlue,
            initialBoidCount,
            fixedTickRate,
            spawnExtent,
            worldHalfExtent,
            minSpeed,
            maxSpeed,
            maxForce,
            neighborRadius,
            separationRadius,
            separationWeight,
            alignmentWeight,
            cohesionWeight,
            maxNeighbors,
            targetSamplesPerCell,
            neighborRefreshIntervalTicks,
            newFastMath,
            steeringExecutionMode,
            randomSeed
        );
    }

    public SimulationConfig withSteeringExecutionMode(SteeringExecutionMode newSteeringExecutionMode) {
        return new SimulationConfig(
            width,
            height,
            title,
            backgroundRed,
            backgroundGreen,
            backgroundBlue,
            initialBoidCount,
            fixedTickRate,
            spawnExtent,
            worldHalfExtent,
            minSpeed,
            maxSpeed,
            maxForce,
            neighborRadius,
            separationRadius,
            separationWeight,
            alignmentWeight,
            cohesionWeight,
            maxNeighbors,
            targetSamplesPerCell,
            neighborRefreshIntervalTicks,
            fastMath,
            newSteeringExecutionMode,
            randomSeed
        );
    }
}

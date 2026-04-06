package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.ECS;
import com.ethnicthv.ecs.boid.debug.WorldStatsCollector;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.archetype.EntityCommandBuffer;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.system.SystemGroup;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.SplittableRandom;

public final class BoidSimulation implements AutoCloseable {
    private static final long TPS_SAMPLE_WINDOW_NANOS = 250_000_000L;

    private final SimulationConfig config;
    private final ECS ecs;
    private final ArchetypeWorld world;
    private final ComponentManager componentManager;
    private final SpatialHashGrid spatialHash;
    private final SteeringSystem steeringSystem;

    private final int positionXIndex;
    private final int positionYIndex;
    private final int positionZIndex;
    private final int velocityXIndex;
    private final int velocityYIndex;
    private final int velocityZIndex;
    private final int accelerationXIndex;
    private final int accelerationYIndex;
    private final int accelerationZIndex;
    private final int cellValueIndex;
    private final int neighborCountIndex;

    private int[] boidEntityIds = new int[0];
    private long fixedTickCount;
    private long lastFixedStepNanos;
    private long totalFixedStepNanos;
    private long firstTickCompletedAtNanos;
    private long lastTickCompletedAtNanos;
    private long tpsWindowStartNanos;
    private long tpsWindowStartTick;
    private double lastMeasuredTicksPerSecond;
    private boolean bootstrapped;
    private boolean closed;

    public BoidSimulation(SimulationConfig config) {
        this.config = config;
        this.spatialHash = new SpatialHashGrid(config);
        this.steeringSystem = new SteeringSystem(spatialHash, config);
        this.ecs = ECS.builder()
            .addSystem(new ResetAccelerationSystem())
            .addSystem(new SpatialHashSystem(spatialHash))
            .addSystem(new BuildNeighborSystem(spatialHash))
            .addSystem(steeringSystem)
            .addSystem(new MovementSystem(config.worldHalfExtent(), config.minSpeed(), config.maxSpeed(), spatialHash))
            .build();
        this.world = ecs.getWorld();
        this.componentManager = world.getComponentManager();

        positionXIndex = componentManager.getDescriptor(Position3.class).getFieldIndex("x");
        positionYIndex = componentManager.getDescriptor(Position3.class).getFieldIndex("y");
        positionZIndex = componentManager.getDescriptor(Position3.class).getFieldIndex("z");
        velocityXIndex = componentManager.getDescriptor(Velocity3.class).getFieldIndex("x");
        velocityYIndex = componentManager.getDescriptor(Velocity3.class).getFieldIndex("y");
        velocityZIndex = componentManager.getDescriptor(Velocity3.class).getFieldIndex("z");
        accelerationXIndex = componentManager.getDescriptor(Acceleration3.class).getFieldIndex("x");
        accelerationYIndex = componentManager.getDescriptor(Acceleration3.class).getFieldIndex("y");
        accelerationZIndex = componentManager.getDescriptor(Acceleration3.class).getFieldIndex("z");
        cellValueIndex = componentManager.getDescriptor(CellKey.class).getFieldIndex("cellId");
        neighborCountIndex = componentManager.getDescriptor(NeighborBuffer.class).getFieldIndex("count");
    }

    public void bootstrap() {
        if (bootstrapped) {
            return;
        }

        resetBoids(config.initialBoidCount(), config.randomSeed());
        bootstrapped = true;
    }

    public void resetBoids(int boidCount, long randomSeed) {
        ensureAlive();

        destroyExistingBoids();

        fixedTickCount = 0L;
        lastFixedStepNanos = 0L;
        totalFixedStepNanos = 0L;
        firstTickCompletedAtNanos = 0L;
        lastTickCompletedAtNanos = 0L;
        tpsWindowStartNanos = 0L;
        tpsWindowStartTick = 0L;
        lastMeasuredTicksPerSecond = 0.0;
        boidEntityIds = new int[boidCount];
        spatialHash.reserveBoids(boidCount);

        SplittableRandom random = new SplittableRandom(randomSeed);
        for (int i = 0; i < boidCount; i++) {
            int entityId = ecs.createEntity(Position3.class, Velocity3.class, Acceleration3.class, CellKey.class, NeighborBuffer.class);
            boidEntityIds[i] = entityId;

            float px = randomRange(random, -config.spawnExtent(), config.spawnExtent());
            float py = randomRange(random, -config.spawnExtent(), config.spawnExtent());
            float pz = randomRange(random, -config.spawnExtent(), config.spawnExtent());

            float dirX = randomRange(random, -1.0f, 1.0f);
            float dirY = randomRange(random, -1.0f, 1.0f);
            float dirZ = randomRange(random, -1.0f, 1.0f);
            float dirLength = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
            if (dirLength < 0.0001f) {
                dirX = 1.0f;
                dirY = 0.0f;
                dirZ = 0.0f;
                dirLength = 1.0f;
            }

            float speed = randomRange(random, config.minSpeed(), config.maxSpeed());
            float invLength = speed / dirLength;
            float vx = dirX * invLength;
            float vy = dirY * invLength;
            float vz = dirZ * invLength;

            ecs.addComponent(entityId, Position3.class, (Position3Handle handle) -> {
                handle.setX(px);
                handle.setY(py);
                handle.setZ(pz);
            });
            ecs.addComponent(entityId, Velocity3.class, (Velocity3Handle handle) -> {
                handle.setX(vx);
                handle.setY(vy);
                handle.setZ(vz);
            });
            ecs.addComponent(entityId, Acceleration3.class, (Acceleration3Handle handle) -> {
                handle.setX(0.0f);
                handle.setY(0.0f);
                handle.setZ(0.0f);
            });
            writeCellKey(entityId, 0);
            spatialHash.publishRenderPositionByIndex(i, px, py, pz);
        }
        spatialHash.bindBoids(boidEntityIds);
    }

    public void stepFixed() {
        ensureBootstrapped();
        long startedAt = System.nanoTime();
        ecs.getSystemManager().updateGroup(SystemGroup.SIMULATION, fixedDeltaTime());
        long completedAt = System.nanoTime();
        lastFixedStepNanos = completedAt - startedAt;
        totalFixedStepNanos += lastFixedStepNanos;
        fixedTickCount++;
        updateTicksPerSecond(completedAt);
    }

    public void stepFixed(int tickCount) {
        for (int i = 0; i < tickCount; i++) {
            stepFixed();
        }
    }

    public float fixedDeltaTime() {
        return 1.0f / config.fixedTickRate();
    }

    public SimulationStats getStats() {
        double averageTicksPerSecond = averageTicksPerSecond();
        return new SimulationStats(
            boidEntityIds.length,
            world.getEntityCount(),
            fixedTickCount,
            fixedDeltaTime(),
            steeringSystem.executionMode(),
            nanosToMillis(lastFixedStepNanos),
            nanosToMillis(fixedTickCount == 0L ? 0L : totalFixedStepNanos / fixedTickCount),
            nanosToMillis(steeringSystem.lastUpdateNanos()),
            nanosToMillis(steeringSystem.averageUpdateNanos()),
            currentTicksPerSecond(averageTicksPerSecond),
            averageTicksPerSecond
        );
    }

    public SteeringExecutionMode steeringExecutionMode() {
        return steeringSystem.executionMode();
    }

    public void setSteeringExecutionMode(SteeringExecutionMode executionMode) {
        ensureAlive();
        steeringSystem.setExecutionMode(executionMode);
    }

    public SteeringExecutionMode toggleSteeringExecutionMode() {
        ensureAlive();
        SteeringExecutionMode toggledMode = steeringSystem.executionMode().toggle();
        steeringSystem.setExecutionMode(toggledMode);
        return toggledMode;
    }

    public int boidCount() {
        return boidEntityIds.length;
    }

    public float worldHalfExtent() {
        return config.worldHalfExtent();
    }

    public int copyPositions(float[] target) {
        return copyPositions(target, 1);
    }

    public int copyPositions(float[] target, int stride) {
        ensureBootstrapped();
        return spatialHash.copyRenderPositions(target, stride);
    }

    public float separationWeight() {
        return steeringSystem.separationWeight();
    }

    public void setSeparationWeight(float separationWeight) {
        ensureAlive();
        steeringSystem.setSeparationWeight(separationWeight);
    }

    public float alignmentWeight() {
        return steeringSystem.alignmentWeight();
    }

    public void setAlignmentWeight(float alignmentWeight) {
        ensureAlive();
        steeringSystem.setAlignmentWeight(alignmentWeight);
    }

    public float cohesionWeight() {
        return steeringSystem.cohesionWeight();
    }

    public void setCohesionWeight(float cohesionWeight) {
        ensureAlive();
        steeringSystem.setCohesionWeight(cohesionWeight);
    }

    public float maxForce() {
        return steeringSystem.maxForce();
    }

    public void setMaxForce(float maxForce) {
        ensureAlive();
        steeringSystem.setMaxForce(maxForce);
    }

    public void resetSteeringParameters() {
        ensureAlive();
        steeringSystem.setSeparationWeight(config.separationWeight());
        steeringSystem.setAlignmentWeight(config.alignmentWeight());
        steeringSystem.setCohesionWeight(config.cohesionWeight());
        steeringSystem.setMaxForce(config.maxForce());
    }

    public WorldStatsCollector.WorldStatsSnapshot collectWorldStats() {
        ensureAlive();
        return WorldStatsCollector.collect(world);
    }

    public BoidSnapshot readBoid(int boidIndex) {
        ensureBootstrapped();
        if (boidIndex < 0 || boidIndex >= boidEntityIds.length) {
            throw new IndexOutOfBoundsException("boidIndex=" + boidIndex + ", size=" + boidEntityIds.length);
        }

        int entityId = boidEntityIds[boidIndex];
        MemorySegment positionSegment = world.getComponent(entityId, Position3.class);
        MemorySegment velocitySegment = world.getComponent(entityId, Velocity3.class);
        MemorySegment accelerationSegment = world.getComponent(entityId, Acceleration3.class);
        MemorySegment cellSegment = world.getComponent(entityId, CellKey.class);
        if (positionSegment == null || velocitySegment == null || accelerationSegment == null || cellSegment == null) {
            throw new IllegalStateException("Boid entity " + entityId + " is missing required components");
        }

        try (ComponentManager.BoundHandle position = componentManager.acquireBoundHandle(Position3.class, positionSegment);
             ComponentManager.BoundHandle velocity = componentManager.acquireBoundHandle(Velocity3.class, velocitySegment);
             ComponentManager.BoundHandle acceleration = componentManager.acquireBoundHandle(Acceleration3.class, accelerationSegment);
             ComponentManager.BoundHandle cell = componentManager.acquireBoundHandle(CellKey.class, cellSegment)) {
            ComponentHandle positionHandle = position.handle();
            ComponentHandle velocityHandle = velocity.handle();
            ComponentHandle accelerationHandle = acceleration.handle();
            ComponentHandle cellHandle = cell.handle();
            return new BoidSnapshot(
                entityId,
                positionHandle.getFloat(positionXIndex),
                positionHandle.getFloat(positionYIndex),
                positionHandle.getFloat(positionZIndex),
                velocityHandle.getFloat(velocityXIndex),
                velocityHandle.getFloat(velocityYIndex),
                velocityHandle.getFloat(velocityZIndex),
                accelerationHandle.getFloat(accelerationXIndex),
                accelerationHandle.getFloat(accelerationYIndex),
                accelerationHandle.getFloat(accelerationZIndex),
                cellHandle.getInt(cellValueIndex)
            );
        }
    }

    public void setBoidState(
        int boidIndex,
        float positionX,
        float positionY,
        float positionZ,
        float velocityX,
        float velocityY,
        float velocityZ
    ) {
        ensureBootstrapped();
        if (boidIndex < 0 || boidIndex >= boidEntityIds.length) {
            throw new IndexOutOfBoundsException("boidIndex=" + boidIndex + ", size=" + boidEntityIds.length);
        }

        int entityId = boidEntityIds[boidIndex];
        ecs.addComponent(entityId, Position3.class, (Position3Handle handle) -> {
            handle.setX(positionX);
            handle.setY(positionY);
            handle.setZ(positionZ);
        });
        ecs.addComponent(entityId, Velocity3.class, (Velocity3Handle handle) -> {
            handle.setX(velocityX);
            handle.setY(velocityY);
            handle.setZ(velocityZ);
        });
        ecs.addComponent(entityId, Acceleration3.class, (Acceleration3Handle handle) -> {
            handle.setX(0.0f);
            handle.setY(0.0f);
            handle.setZ(0.0f);
        });
        writeCellKey(entityId, 0);
        spatialHash.publishRenderPositionByIndex(boidIndex, positionX, positionY, positionZ);
    }

    int readNeighborCount(int boidIndex) {
        ensureBootstrapped();
        if (boidIndex < 0 || boidIndex >= boidEntityIds.length) {
            throw new IndexOutOfBoundsException("boidIndex=" + boidIndex + ", size=" + boidEntityIds.length);
        }

        int entityId = boidEntityIds[boidIndex];
        MemorySegment neighborSegment = world.getComponent(entityId, NeighborBuffer.class);
        if (neighborSegment == null) {
            throw new IllegalStateException("Boid entity " + entityId + " is missing NeighborBuffer");
        }

        try (ComponentManager.BoundHandle neighbor = componentManager.acquireBoundHandle(NeighborBuffer.class, neighborSegment)) {
            return neighbor.handle().getInt(neighborCountIndex);
        }
    }

    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ecs.close();
    }

    private void ensureAlive() {
        if (closed) {
            throw new IllegalStateException("Simulation has been closed");
        }
    }

    private void ensureBootstrapped() {
        if (!bootstrapped) {
            throw new IllegalStateException("Simulation must be bootstrapped before stepping");
        }
    }

    private void writeCellKey(int entityId, int cellKey) {
        MemorySegment cellSegment = world.getComponent(entityId, CellKey.class);
        if (cellSegment == null) {
            throw new IllegalStateException("Boid entity " + entityId + " is missing CellKey");
        }

        try (ComponentManager.BoundHandle cell = componentManager.acquireBoundHandle(CellKey.class, cellSegment)) {
            cell.handle().setInt(cellValueIndex, cellKey);
        }
    }

    private static float randomRange(SplittableRandom random, float min, float max) {
        return min + (float) random.nextDouble() * (max - min);
    }

    private void destroyExistingBoids() {
        if (boidEntityIds.length == 0) {
            return;
        }

        try (Arena commandArena = Arena.ofConfined();
             EntityCommandBuffer commandBuffer = new EntityCommandBuffer(commandArena)) {
            EntityCommandBuffer.ParallelWriter writer = commandBuffer.asParallelWriter(world);
            for (int entityId : boidEntityIds) {
                writer.destroyEntity(entityId);
            }
            commandBuffer.playback(world);
        }
    }

    private void updateTicksPerSecond(long completedAt) {
        lastTickCompletedAtNanos = completedAt;
        if (firstTickCompletedAtNanos == 0L) {
            firstTickCompletedAtNanos = completedAt;
            tpsWindowStartNanos = completedAt;
            tpsWindowStartTick = fixedTickCount;
            return;
        }

        long sampleElapsed = completedAt - tpsWindowStartNanos;
        if (sampleElapsed >= TPS_SAMPLE_WINDOW_NANOS) {
            long ticksInWindow = fixedTickCount - tpsWindowStartTick;
            if (ticksInWindow > 0) {
                lastMeasuredTicksPerSecond = ticksInWindow * 1_000_000_000.0 / sampleElapsed;
            }
            tpsWindowStartNanos = completedAt;
            tpsWindowStartTick = fixedTickCount;
        }
    }

    private double currentTicksPerSecond(double averageTicksPerSecond) {
        if (fixedTickCount == 0L) {
            return 0.0;
        }
        return lastMeasuredTicksPerSecond > 0.0 ? lastMeasuredTicksPerSecond : averageTicksPerSecond;
    }

    private double averageTicksPerSecond() {
        if (fixedTickCount == 0L || firstTickCompletedAtNanos == 0L || lastTickCompletedAtNanos <= firstTickCompletedAtNanos) {
            return 0.0;
        }
        long elapsedNanos = lastTickCompletedAtNanos - firstTickCompletedAtNanos;
        return elapsedNanos <= 0L ? 0.0 : fixedTickCount * 1_000_000_000.0 / elapsedNanos;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    public record SimulationStats(
        int boidCount,
        int entityCount,
        long fixedTickCount,
        float fixedDeltaTime,
        SteeringExecutionMode steeringExecutionMode,
        double lastFixedStepMillis,
        double averageFixedStepMillis,
        double lastSteeringMillis,
        double averageSteeringMillis,
        double currentWorldTicksPerSecond,
        double averageWorldTicksPerSecond
    ) {
    }

    public record BoidSnapshot(
        int entityId,
        float positionX,
        float positionY,
        float positionZ,
        float velocityX,
        float velocityY,
        float velocityZ,
        float accelerationX,
        float accelerationY,
        float accelerationZ,
        int cellKey
    ) {
    }
}

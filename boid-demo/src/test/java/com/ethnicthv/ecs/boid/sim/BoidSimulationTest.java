package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.boid.debug.WorldStatsCollector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoidSimulationTest {
    private static final float POSITION_TOLERANCE = 0.0001f;

    @Test
    void spawnsAndStepsTwentyFiveThousandBoidsHeadlessly() {
        SimulationConfig config = SimulationConfig.defaultConfig()
            .withInitialBoidCount(25_000)
            .withRandomSeed(42L);

        try (BoidSimulation simulation = new BoidSimulation(config)) {
            simulation.bootstrap();
            BoidSimulation.SimulationStats initial = simulation.getStats();
            BoidSimulation.BoidSnapshot before = simulation.readBoid(0);

            simulation.stepFixed(3);

            BoidSimulation.SimulationStats after = simulation.getStats();
            BoidSimulation.BoidSnapshot current = simulation.readBoid(0);
            float dx = current.positionX() - before.positionX();
            float dy = current.positionY() - before.positionY();
            float dz = current.positionZ() - before.positionZ();

            assertEquals(25_000, initial.boidCount());
            assertEquals(25_000, initial.entityCount());
            assertEquals(25_000, after.boidCount());
            assertEquals(25_000, after.entityCount());
            assertEquals(3L, after.fixedTickCount());
            assertEquals(SteeringExecutionMode.SEQUENTIAL, after.steeringExecutionMode());
            assertTrue(dx * dx + dy * dy + dz * dz > 0.0f, "boid position should change after fixed-step updates");
            assertTrue(current.cellKey() >= 0, "cell key should be populated by the spatial hash");
            assertTrue(after.lastFixedStepMillis() > 0.0, "fixed-step timing should be recorded");
            assertTrue(after.lastSteeringMillis() > 0.0, "steering timing should be recorded");
            assertTrue(after.currentWorldTicksPerSecond() > 0.0, "current world TPS should be recorded");
            assertTrue(after.averageWorldTicksPerSecond() > 0.0, "average world TPS should be recorded");
        }
    }

    @Test
    void nearbyBoidsSteerAwayAcrossWrappedWorldBounds() {
        SimulationConfig config = SimulationConfig.defaultConfig()
            .withInitialBoidCount(2)
            .withSpeedRange(0.0f, 8.0f)
            .withFlocking(8.0f, 6.0f, 2.5f, 0.0f, 0.0f, 8.0f)
            .withRandomSeed(7L);

        try (BoidSimulation simulation = new BoidSimulation(config)) {
            simulation.bootstrap();
            simulation.setBoidState(0, 149.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
            simulation.setBoidState(1, -149.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

            simulation.stepFixed();

            BoidSimulation.BoidSnapshot positiveEdgeBoid = simulation.readBoid(0);
            BoidSimulation.BoidSnapshot negativeEdgeBoid = simulation.readBoid(1);

            assertTrue(
                positiveEdgeBoid.accelerationX() < 0.0f,
                "boid at +X edge should separate left across wrapped bounds, actual=" + positiveEdgeBoid.accelerationX()
            );
            assertTrue(
                negativeEdgeBoid.accelerationX() > 0.0f,
                "boid at -X edge should separate right across wrapped bounds, actual=" + negativeEdgeBoid.accelerationX()
            );
            assertTrue(positiveEdgeBoid.cellKey() >= 0);
            assertTrue(negativeEdgeBoid.cellKey() >= 0);
        }
    }

    @Test
    void sequentialAndParallelSteeringStayInSyncWithinTolerance() {
        SimulationConfig sequentialConfig = SimulationConfig.defaultConfig()
            .withInitialBoidCount(512)
            .withRandomSeed(99L)
            .withSteeringExecutionMode(SteeringExecutionMode.SEQUENTIAL);
        SimulationConfig parallelConfig = sequentialConfig.withSteeringExecutionMode(SteeringExecutionMode.PARALLEL);

        try (BoidSimulation sequentialSimulation = new BoidSimulation(sequentialConfig);
             BoidSimulation parallelSimulation = new BoidSimulation(parallelConfig)) {
            sequentialSimulation.bootstrap();
            parallelSimulation.bootstrap();

            sequentialSimulation.stepFixed(6);
            parallelSimulation.stepFixed(6);

            BoidSimulation.SimulationStats parallelStats = parallelSimulation.getStats();
            assertEquals(SteeringExecutionMode.PARALLEL, parallelStats.steeringExecutionMode());
            assertTrue(parallelStats.averageSteeringMillis() >= 0.0);

            for (int i = 0; i < sequentialStats(sequentialSimulation).boidCount(); i++) {
                BoidSimulation.BoidSnapshot sequentialBoid = sequentialSimulation.readBoid(i);
                BoidSimulation.BoidSnapshot parallelBoid = parallelSimulation.readBoid(i);

                assertEquals(sequentialBoid.entityId(), parallelBoid.entityId());
                assertEquals(sequentialBoid.cellKey(), parallelBoid.cellKey());
                assertEquals(sequentialBoid.positionX(), parallelBoid.positionX(), POSITION_TOLERANCE);
                assertEquals(sequentialBoid.positionY(), parallelBoid.positionY(), POSITION_TOLERANCE);
                assertEquals(sequentialBoid.positionZ(), parallelBoid.positionZ(), POSITION_TOLERANCE);
                assertEquals(sequentialBoid.velocityX(), parallelBoid.velocityX(), POSITION_TOLERANCE);
                assertEquals(sequentialBoid.velocityY(), parallelBoid.velocityY(), POSITION_TOLERANCE);
                assertEquals(sequentialBoid.velocityZ(), parallelBoid.velocityZ(), POSITION_TOLERANCE);
                assertEquals(sequentialBoid.accelerationX(), parallelBoid.accelerationX(), POSITION_TOLERANCE);
                assertEquals(sequentialBoid.accelerationY(), parallelBoid.accelerationY(), POSITION_TOLERANCE);
                assertEquals(sequentialBoid.accelerationZ(), parallelBoid.accelerationZ(), POSITION_TOLERANCE);
            }
        }
    }

    @Test
    void bulkPositionCopyMatchesPerBoidSnapshot() {
        SimulationConfig config = SimulationConfig.defaultConfig()
            .withInitialBoidCount(8)
            .withRandomSeed(123L);

        try (BoidSimulation simulation = new BoidSimulation(config)) {
            simulation.bootstrap();
            simulation.stepFixed(2);

            float[] positions = new float[simulation.boidCount() * 3];
            simulation.copyPositions(positions);

            for (int i = 0; i < simulation.boidCount(); i++) {
                BoidSimulation.BoidSnapshot boid = simulation.readBoid(i);
                int baseIndex = i * 3;
                assertEquals(boid.positionX(), positions[baseIndex], POSITION_TOLERANCE);
                assertEquals(boid.positionY(), positions[baseIndex + 1], POSITION_TOLERANCE);
                assertEquals(boid.positionZ(), positions[baseIndex + 2], POSITION_TOLERANCE);
            }
        }
    }

    @Test
    void sampledPositionCopyReturnsEveryNthBoid() {
        SimulationConfig config = SimulationConfig.defaultConfig()
            .withInitialBoidCount(9)
            .withRandomSeed(555L);

        try (BoidSimulation simulation = new BoidSimulation(config)) {
            simulation.bootstrap();
            float[] sampled = new float[((simulation.boidCount() + 1) / 2) * 3];
            int copied = simulation.copyPositions(sampled, 2);

            assertEquals(5, copied);

            for (int i = 0; i < copied; i++) {
                BoidSimulation.BoidSnapshot boid = simulation.readBoid(i * 2);
                int baseIndex = i * 3;
                assertEquals(boid.positionX(), sampled[baseIndex], POSITION_TOLERANCE);
                assertEquals(boid.positionY(), sampled[baseIndex + 1], POSITION_TOLERANCE);
                assertEquals(boid.positionZ(), sampled[baseIndex + 2], POSITION_TOLERANCE);
            }
        }
    }

    @Test
    void renderSnapshotTracksManualStateChangesBeforeNextFixedStep() {
        SimulationConfig config = SimulationConfig.defaultConfig()
            .withInitialBoidCount(2)
            .withRandomSeed(77L);

        try (BoidSimulation simulation = new BoidSimulation(config)) {
            simulation.bootstrap();
            simulation.setBoidState(0, 11.5f, -7.25f, 3.0f, 0.0f, 0.0f, 0.0f);

            float[] positions = new float[simulation.boidCount() * 3];
            int copied = simulation.copyPositions(positions);

            assertEquals(2, copied);
            assertEquals(11.5f, positions[0], POSITION_TOLERANCE);
            assertEquals(-7.25f, positions[1], POSITION_TOLERANCE);
            assertEquals(3.0f, positions[2], POSITION_TOLERANCE);
        }
    }

    @Test
    void worldStatsAndRuntimeSteeringControlsReflectCurrentSimulation() {
        SimulationConfig config = SimulationConfig.defaultConfig()
            .withInitialBoidCount(64)
            .withRandomSeed(321L);

        try (BoidSimulation simulation = new BoidSimulation(config)) {
            simulation.bootstrap();
            simulation.setSeparationWeight(2.25f);
            simulation.setAlignmentWeight(1.1f);
            simulation.setCohesionWeight(0.4f);
            simulation.setMaxForce(9.5f);

            WorldStatsCollector.WorldStatsSnapshot worldStats = simulation.collectWorldStats();

            assertEquals(2.25f, simulation.separationWeight(), POSITION_TOLERANCE);
            assertEquals(1.1f, simulation.alignmentWeight(), POSITION_TOLERANCE);
            assertEquals(0.4f, simulation.cohesionWeight(), POSITION_TOLERANCE);
            assertEquals(9.5f, simulation.maxForce(), POSITION_TOLERANCE);
            assertTrue(worldStats.archetypeCount() >= 1);
            assertTrue(worldStats.chunkCount() >= 1);
            assertTrue(worldStats.averageChunkOccupancy() > 0.0f);

            simulation.resetSteeringParameters();

            assertEquals(config.separationWeight(), simulation.separationWeight(), POSITION_TOLERANCE);
            assertEquals(config.alignmentWeight(), simulation.alignmentWeight(), POSITION_TOLERANCE);
            assertEquals(config.cohesionWeight(), simulation.cohesionWeight(), POSITION_TOLERANCE);
            assertEquals(config.maxForce(), simulation.maxForce(), POSITION_TOLERANCE);
        }
    }

    private static BoidSimulation.SimulationStats sequentialStats(BoidSimulation simulation) {
        return simulation.getStats();
    }
}

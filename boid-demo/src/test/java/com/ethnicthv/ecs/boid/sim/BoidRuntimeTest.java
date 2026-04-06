package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.core.execution.SystemThreadMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoidRuntimeTest {

    @Test
    void dedicatedRuntimeAppliesQueuedCommandsAndPublishesSnapshots() throws Exception {
        SimulationConfig config = SimulationConfig.defaultConfig()
            .withInitialBoidCount(64)
            .withSteeringExecutionMode(SteeringExecutionMode.SEQUENTIAL);

        try (BoidRuntime runtime = new BoidRuntime(config, SystemThreadMode.DEDICATED_THREAD)) {
            runtime.bootstrap();

            awaitCondition(Duration.ofSeconds(5), () -> runtime.boidCount() == 64);
            runtime.setSteeringExecutionMode(SteeringExecutionMode.PARALLEL);
            runtime.setSeparationWeight(2.25f);
            runtime.resetBoids(96, 42L);
            runtime.setPaused(true);

            awaitCondition(Duration.ofSeconds(5), () ->
                runtime.boidCount() == 96 &&
                    runtime.steeringExecutionMode() == SteeringExecutionMode.PARALLEL &&
                    Math.abs(runtime.separationWeight() - 2.25f) < 0.0001f &&
                    runtime.isPaused()
            );

            float[] positions = new float[96 * 3];
            int copied = runtime.copyPositions(positions);
            assertEquals(96, copied);
            assertTrue(anyNonZero(positions, copied * 3), "Published positions should contain boid data");
        }
    }

    private static void awaitCondition(Duration timeout, CheckedBooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Condition was not met within " + timeout);
    }

    private static boolean anyNonZero(float[] values, int length) {
        for (int i = 0; i < length; i++) {
            if (Math.abs(values[i]) > 0.0001f) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}

package com.ethnicthv.ecs.runtime;

import com.ethnicthv.ecs.ECS;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.execution.EcsRuntimeState;
import com.ethnicthv.ecs.core.execution.SystemThreadMode;
import com.ethnicthv.ecs.core.system.ISystem;
import com.ethnicthv.ecs.core.system.SystemGroup;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemThreadModeTest {

    @Test
    void mainThreadModeAllowsManualUpdateGroup() {
        CountingSystem system = new CountingSystem();

        try (ECS ecs = ECS.builder()
            .withSystemThreadMode(SystemThreadMode.MAIN_THREAD)
            .addSystem(system, SystemGroup.SIMULATION)
            .build()) {
            ecs.updateGroup(SystemGroup.SIMULATION, 1f / 60f);

            assertEquals(1, system.updateCount.get());
            assertEquals(Thread.currentThread().getName(), system.lastThreadName.get());
            assertEquals(SystemThreadMode.MAIN_THREAD, ecs.systemThreadMode());
        }
    }

    @Test
    void dedicatedThreadModeRunsOnBackgroundThreadAndRejectsManualUpdates() throws Exception {
        CountingSystem system = new CountingSystem();

        try (ECS ecs = ECS.builder()
            .withSystemThreadMode(SystemThreadMode.DEDICATED_THREAD)
            .withSimulationThreadName("ecs-test-sim")
            .withFixedTickRate(120f)
            .addSystem(system, SystemGroup.SIMULATION)
            .build()) {
            ecs.startRuntime();

            assertTrue(system.updated.await(5, TimeUnit.SECONDS), "Dedicated runtime should execute at least one tick");
            assertTrue(system.lastThreadName.get().startsWith("ecs-test-sim"));
            assertTrue(system.updateCount.get() > 0);
            assertEquals(EcsRuntimeState.RUNNING, ecs.runtimeState());

            assertThrows(IllegalStateException.class, () -> ecs.updateGroup(SystemGroup.SIMULATION, 1f / 60f));
            assertThrows(IllegalStateException.class, () -> ecs.getSystemManager().updateGroup(SystemGroup.SIMULATION, 1f / 60f));

            ecs.stopRuntime();
            ecs.awaitRuntimeStop();

            assertEquals(EcsRuntimeState.STOPPED, ecs.runtimeState());
        }
    }

    private static final class CountingSystem implements ISystem {
        private final AtomicInteger updateCount = new AtomicInteger();
        private final AtomicReference<String> lastThreadName = new AtomicReference<>("");
        private final CountDownLatch updated = new CountDownLatch(1);
        private volatile boolean enabled = true;

        @Override
        public void onAwake(ArchetypeWorld world) {
        }

        @Override
        public void onUpdate(float deltaTime) {
            updateCount.incrementAndGet();
            lastThreadName.set(Thread.currentThread().getName());
            updated.countDown();
        }

        @Override
        public void onDispose() {
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}

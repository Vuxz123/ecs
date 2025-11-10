package com.ethnicthv.ecs.benchmark;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.demo.PositionComponent;
import com.ethnicthv.ecs.demo.VelocityComponent;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

/**
 * Compare per-entity structural moves vs true batch structural moves.
 * We isolate structural cost by using the batch API for both paths: single-entity batches vs one large batch.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgs = {"-Xms2G", "-Xmx4G", "--enable-preview", "--add-modules", "jdk.incubator.vector"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class BatchStructuralMoveBenchmark {

    @State(Scope.Thread)
    public static class AddState {
        @Param({"1000", "10000", "100000"})
        public int entityCount;

        public ComponentManager manager;
        public ArchetypeWorld world;
        public Arena arena;
        public int[] eids;

        @Setup(Level.Invocation)
        public void setup() {
            manager = new ComponentManager();
            world = new ArchetypeWorld(manager);
            arena = Arena.ofConfined();
            world.registerComponent(PositionComponent.class);
            world.registerComponent(VelocityComponent.class);

            eids = new int[entityCount];
            for (int i = 0; i < entityCount; i++) {
                int e = world.createEntity();
                // Add Position so structural op is add Velocity
                MemorySegment pos = manager.allocate(PositionComponent.class, arena);
                world.addComponent(e, PositionComponent.class, pos);
                eids[i] = e;
            }
        }

        @TearDown(Level.Invocation)
        public void tearDown() {
            world.close();
            arena.close();
        }
    }

    @State(Scope.Thread)
    public static class RemoveState {
        @Param({"1000", "10000", "100000"})
        public int entityCount;

        public ComponentManager manager;
        public ArchetypeWorld world;
        public Arena arena;
        public int[] eids;

        @Setup(Level.Invocation)
        public void setup() {
            manager = new ComponentManager();
            world = new ArchetypeWorld(manager);
            arena = Arena.ofConfined();
            world.registerComponent(PositionComponent.class);
            world.registerComponent(VelocityComponent.class);

            eids = new int[entityCount];
            for (int i = 0; i < entityCount; i++) {
                int e = world.createEntity();
                // Add Position & Velocity so structural op is remove Velocity
                MemorySegment pos = manager.allocate(PositionComponent.class, arena);
                world.addComponent(e, PositionComponent.class, pos);
                MemorySegment vel = manager.allocate(VelocityComponent.class, arena);
                world.addComponent(e, VelocityComponent.class, vel);
                eids[i] = e;
            }
        }

        @TearDown(Level.Invocation)
        public void tearDown() {
            world.close();
            arena.close();
        }
    }

    // ===== Add Velocity: per-entity (single-entity batches) vs one big batch =====

    @Benchmark
    public void add_perEntity(AddState s) {
        for (int e : s.eids) {
            ArchetypeWorld.EntityBatch one = ArchetypeWorld.EntityBatch.of(e);
            s.world.addComponents(one, VelocityComponent.class);
        }
    }

    @Benchmark
    public void add_batch(AddState s) {
        ArchetypeWorld.EntityBatch batch = ArchetypeWorld.EntityBatch.of(s.eids);
        s.world.addComponents(batch, VelocityComponent.class);
    }

    // ===== Remove Velocity: per-entity vs one big batch =====

    @Benchmark
    public void remove_perEntity(RemoveState s) {
        for (int e : s.eids) {
            ArchetypeWorld.EntityBatch one = ArchetypeWorld.EntityBatch.of(e);
            s.world.removeComponents(one, VelocityComponent.class);
        }
    }

    @Benchmark
    public void remove_batch(RemoveState s) {
        ArchetypeWorld.EntityBatch batch = ArchetypeWorld.EntityBatch.of(s.eids);
        s.world.removeComponents(batch, VelocityComponent.class);
    }
}


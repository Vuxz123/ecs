package com.ethnicthv.ecs.benchmark;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.archetype.EntityCommandBuffer;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.demo.TeamShared;
import com.ethnicthv.ecs.archetype.TestComponent1;
import com.ethnicthv.ecs.archetype.TestComponent2;
import com.ethnicthv.ecs.archetype.TestComponent3;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.Arena;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmarks comparing per-entity structural changes vs ECB batch playback:
 * - Per-entity mutateComponents vs ECB.mutateComponents.
 * - Per-entity setSharedComponent vs ECB.setSharedManaged.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 1, jvmArgs = {"-Xms1G", "-Xmx1G", "--enable-preview", "--add-modules", "jdk.incubator.vector"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class EntityCommandBufferBenchmark {

    @State(Scope.Thread)
    public static class MutateState {
        @Param({"1000", "10000"})
        public int entityCount;

        public ComponentManager manager;
        public ArchetypeWorld world;
        public Arena arena;
        public int[] eids;

        public EntityCommandBuffer ecb;
        public EntityCommandBuffer.ParallelWriter writer;

        @Setup(Level.Invocation)
        public void setup() throws Exception {
            manager = new ComponentManager();
            world = new ArchetypeWorld(manager);
            arena = Arena.ofConfined();

            world.registerComponent(TestComponent1.class);
            world.registerComponent(TestComponent2.class);
            world.registerComponent(TestComponent3.class);

            eids = new int[entityCount];
            for (int i = 0; i < entityCount; i++) {
                eids[i] = world.createEntity(TestComponent1.class);
            }

            Method getArena = ArchetypeWorld.class.getDeclaredMethod("getArena");
            getArena.setAccessible(true);
            Arena worldArena = (Arena) getArena.invoke(world);
            ecb = new EntityCommandBuffer(worldArena);
            writer = ecb.asParallelWriter(world);
        }

        @TearDown(Level.Invocation)
        public void tearDown() {
            world.close();
            arena.close();
        }
    }

    @State(Scope.Thread)
    public static class SharedState {
        @Param({"1000", "10000"})
        public int entityCount;

        public ComponentManager manager;
        public ArchetypeWorld world;
        public Arena arena;
        public int[] eids;

        public EntityCommandBuffer ecb;
        public EntityCommandBuffer.ParallelWriter writer;
        public TeamShared teamBlue;

        @Setup(Level.Invocation)
        public void setup() throws Exception {
            manager = new ComponentManager();
            world = new ArchetypeWorld(manager);
            arena = Arena.ofConfined();

            world.registerComponent(TestComponent1.class);
            world.registerComponent(TeamShared.class);

            eids = new int[entityCount];
            for (int i = 0; i < entityCount; i++) {
                eids[i] = world.createEntity(TestComponent1.class);
            }

            teamBlue = new TeamShared("Blue");
            int probe = world.createEntity(TestComponent1.class);
            world.setSharedComponent(probe, teamBlue);

            Method getArena = ArchetypeWorld.class.getDeclaredMethod("getArena");
            getArena.setAccessible(true);
            Arena worldArena = (Arena) getArena.invoke(world);
            ecb = new EntityCommandBuffer(worldArena);
            writer = ecb.asParallelWriter(world);
        }

        @TearDown(Level.Invocation)
        public void tearDown() {
            world.close();
            arena.close();
        }
    }

    // ===== Structural mutate: per-entity vs ECB =====

    @Benchmark
    public void mutate_perEntity(MutateState s) {
        Class<?>[] adds = {TestComponent2.class, TestComponent3.class};
        Class<?>[] rems = {TestComponent1.class};
        for (int e : s.eids) {
            ArchetypeWorld.EntityBatch one = ArchetypeWorld.EntityBatch.of(e);
            s.world.mutateComponents(one, adds, rems);
        }
    }

    @Benchmark
    public void mutate_ecb_batch(MutateState s) {
        Class<?>[] adds = {TestComponent2.class, TestComponent3.class};
        Class<?>[] rems = {TestComponent1.class};
        for (int e : s.eids) {
            s.writer.mutateComponents(e, adds, rems);
        }
        s.ecb.playback(s.world);
    }

    // ===== Shared managed: per-entity vs ECB =====

    @Benchmark
    public void shared_perEntity(SharedState s) {
        for (int e : s.eids) {
            s.world.setSharedComponent(e, s.teamBlue);
        }
    }

    @Benchmark
    public void shared_ecb_batch(SharedState s) {
        for (int e : s.eids) {
            s.writer.setSharedManaged(e, s.teamBlue);
        }
        s.ecb.playback(s.world);
    }
}


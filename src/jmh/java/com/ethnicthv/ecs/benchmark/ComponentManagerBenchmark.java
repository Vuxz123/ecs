package com.ethnicthv.ecs.benchmark;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.demo.HealthComponent;
import com.ethnicthv.ecs.demo.PositionComponent;
import com.ethnicthv.ecs.demo.TransformComponent;
import com.ethnicthv.ecs.demo.VelocityComponent;
import com.ethnicthv.ecs.core.api.archetype.IQuery;
import com.ethnicthv.ecs.core.system.SystemManager;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Query;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive benchmark for Component Manager + Archetype ECS system using JMH.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {"-Xms2G", "-Xmx4G", "--enable-preview", "--add-modules", "jdk.incubator.vector"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ComponentManagerBenchmark {

    // ===== Systems for new API benchmarks =====
    static class ReadWriteSystem {
        IQuery q;
        Blackhole bh;
        int POS_X, POS_Y, VEL_VX, VEL_VY;
        int count;
        void run(){ count = 0; q.runQuery(); }
        @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = { PositionComponent.class, VelocityComponent.class })
        private void step(@com.ethnicthv.ecs.core.system.annotation.Component(type = PositionComponent.class) ComponentHandle pos,
                          @com.ethnicthv.ecs.core.system.annotation.Component(type = VelocityComponent.class) ComponentHandle vel) {
            float x = pos.getFloat(POS_X);
            float y = pos.getFloat(POS_Y);
            float vx = vel.getFloat(VEL_VX);
            float vy = vel.getFloat(VEL_VY);
            if (bh != null) bh.consume(x + y + vx + vy);
            count++;
        }
    }

    static class UpdateSystem {
        IQuery q;
        int POS_X, VEL_VX;
        void run(){ q.runQuery(); }
        @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = { PositionComponent.class, VelocityComponent.class })
        private void step(@com.ethnicthv.ecs.core.system.annotation.Component(type = PositionComponent.class) ComponentHandle pos,
                          @com.ethnicthv.ecs.core.system.annotation.Component(type = VelocityComponent.class) ComponentHandle vel) {
            float x = pos.getFloat(POS_X);
            float vx = vel.getFloat(VEL_VX);
            pos.setFloat(POS_X, x + vx);
        }
    }

    static class MovementSequentialSystem {
        IQuery q;
        int POS_X, VEL_VX;
        void run(){ q.runQuery(); }
        @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = { PositionComponent.class, VelocityComponent.class })
        private void step(@com.ethnicthv.ecs.core.system.annotation.Component(type = PositionComponent.class) ComponentHandle pos,
                          @com.ethnicthv.ecs.core.system.annotation.Component(type = VelocityComponent.class) ComponentHandle vel) {
            float x = pos.getFloat(POS_X);
            float vx = vel.getFloat(VEL_VX);
            pos.setFloat(POS_X, x + vx * 0.016f);
        }
    }

    static class MovementParallelSystem {
        IQuery q;
        int POS_X, VEL_VX;
        void run(){ q.runQuery(); }
        @Query(fieldInject = "q", mode = ExecutionMode.PARALLEL, with = { PositionComponent.class, VelocityComponent.class })
        private void step(@com.ethnicthv.ecs.core.system.annotation.Component(type = PositionComponent.class) ComponentHandle pos,
                          @com.ethnicthv.ecs.core.system.annotation.Component(type = VelocityComponent.class) ComponentHandle vel) {
            float x = pos.getFloat(POS_X);
            float vx = vel.getFloat(VEL_VX);
            pos.setFloat(POS_X, x + vx * 0.016f);
        }
    }

    static class RenderSystem {
        IQuery q;
        Blackhole bh;
        int POS_X, POS_Y;
        int count;
        void run(){ count = 0; q.runQuery(); }
        @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = { PositionComponent.class })
        private void step(@com.ethnicthv.ecs.core.system.annotation.Component(type = PositionComponent.class) ComponentHandle pos) {
            float x = pos.getFloat(POS_X);
            float y = pos.getFloat(POS_Y);
            if (bh != null) bh.consume(x * x + y * y);
            count++;
        }
    }

    static class ThroughputPosReadSystem {
        IQuery q;
        int POS_X;
        void run(){ q.runQuery(); }
        @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = { PositionComponent.class })
        private void step(@com.ethnicthv.ecs.core.system.annotation.Component(type = PositionComponent.class) ComponentHandle pos) {
            // Minimal work to emulate iteration hot-path
            pos.getFloat(POS_X);
        }
    }

    static class EntitiesPerSecondSystem {
        IQuery q;
        int POS_X, POS_Y, VEL_VX, VEL_VY;
        void run(){ q.runQuery(); }
        @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = { PositionComponent.class, VelocityComponent.class })
        private void step(@com.ethnicthv.ecs.core.system.annotation.Component(type = PositionComponent.class) ComponentHandle pos,
                          @com.ethnicthv.ecs.core.system.annotation.Component(type = VelocityComponent.class) ComponentHandle vel) {
            float x = pos.getFloat(POS_X);
            float y = pos.getFloat(POS_Y);
            float vx = vel.getFloat(VEL_VX);
            float vy = vel.getFloat(VEL_VY);
            pos.setFloat(POS_X, x + vx);
            pos.setFloat(POS_Y, y + vy);
        }
    }

    @State(Scope.Thread)
    public static class BenchmarkState {
        @Param({"10", "100", "1000", "10000", "100000", "1000000"})
        public int entityCount;

        public ComponentManager manager;
        public ArchetypeWorld world;
        public Arena arena;
        public SystemManager systemManager;

        // For specific benchmarks
        public int[] entities;
        public int POS_X, POS_Y, VEL_VX, VEL_VY;

        @Setup(Level.Invocation)
        public void setupInvocation() {
            manager = new ComponentManager();
            world = new ArchetypeWorld(manager);
            systemManager = new SystemManager(world);
            arena = Arena.ofConfined();

            // Register common components
            world.registerComponent(PositionComponent.class);
            world.registerComponent(VelocityComponent.class);
            world.registerComponent(TransformComponent.class);
            world.registerComponent(HealthComponent.class);

            // Resolve field indices
            POS_X = manager.getDescriptor(PositionComponent.class).getFieldIndex("x");
            POS_Y = manager.getDescriptor(PositionComponent.class).getFieldIndex("y");
            VEL_VX = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vx");
            VEL_VY = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vy");

            // Pre-create entities for benchmarks that need them
            entities = new int[entityCount];
            for (int i = 0; i < entityCount; i++) {
                entities[i] = world.createEntity();
            }
        }

        @TearDown(Level.Invocation)
        public void teardownInvocation() {
            world.close();
            arena.close();
            System.gc(); // Suggest GC between invocations
        }
    }

    @Benchmark
    public void componentRegistration(Blackhole bh) {
        ComponentManager manager = new ComponentManager();
        bh.consume(manager.registerComponent(PositionComponent.class));
        bh.consume(manager.registerComponent(VelocityComponent.class));
        bh.consume(manager.registerComponent(TransformComponent.class));
        bh.consume(manager.registerComponent(HealthComponent.class));
    }

    @Benchmark
    public void entityCreation(BenchmarkState state, Blackhole bh) {
        for (int i = 0; i < state.entityCount; i++) {
            bh.consume(state.world.createEntity());
        }
    }

    @Benchmark
    public void componentAddition(BenchmarkState state) {
        for (int i = 0; i < state.entityCount; i++) {
            MemorySegment pos = state.manager.allocate(PositionComponent.class, state.arena);
            try (ComponentManager.BoundHandle h = state.manager.acquireBoundHandle(PositionComponent.class, pos)) {
                h.handle().setFloat(state.POS_X, i);
                h.handle().setFloat(state.POS_Y, i * 2);
            }
            state.world.addComponent(state.entities[i], PositionComponent.class, pos);
        }
    }

    @State(Scope.Thread)
    public static class QueryState {
        @Param({"10", "100", "1000", "10000", "100000", "1000000"})
        public int entityCount;

        public ComponentManager manager;
        public ArchetypeWorld world;
        public Arena arena;
        public SystemManager systemManager;
        public int POS_X, POS_Y, VEL_VX, VEL_VY;
        public ReadWriteSystem rwSys;
        public UpdateSystem updSys;

        @Setup(Level.Trial)
        public void setupTrial() {
            manager = new ComponentManager();
            world = new ArchetypeWorld(manager);
            systemManager = new SystemManager(world);
            arena = Arena.ofConfined();

            world.registerComponent(PositionComponent.class);
            world.registerComponent(VelocityComponent.class);

            POS_X = manager.getDescriptor(PositionComponent.class).getFieldIndex("x");
            POS_Y = manager.getDescriptor(PositionComponent.class).getFieldIndex("y");
            VEL_VX = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vx");
            VEL_VY = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vy");

            // Setup entities
            for (int i = 0; i < entityCount; i++) {
                int e = world.createEntity();

                MemorySegment pos = manager.allocate(PositionComponent.class, arena);
                try(var h = manager.acquireBoundHandle(PositionComponent.class, pos)) {
                    h.handle().setFloat(POS_X, i);
                    h.handle().setFloat(POS_Y, i * 2);
                }
                world.addComponent(e, PositionComponent.class, pos);

                if (i % 2 == 0) { // 50% have velocity
                    MemorySegment vel = manager.allocate(VelocityComponent.class, arena);
                    try(var h = manager.acquireBoundHandle(VelocityComponent.class, vel)) {
                        h.handle().setFloat(VEL_VX, 1.0f);
                        h.handle().setFloat(VEL_VY, -1.0f);
                    }
                    world.addComponent(e, VelocityComponent.class, vel);
                }
            }

            // Systems
            rwSys = new ReadWriteSystem();
            rwSys.POS_X = POS_X; rwSys.POS_Y = POS_Y; rwSys.VEL_VX = VEL_VX; rwSys.VEL_VY = VEL_VY;
            systemManager.registerSystem(rwSys);

            updSys = new UpdateSystem();
            updSys.POS_X = POS_X; updSys.VEL_VX = VEL_VX;
            systemManager.registerSystem(updSys);
        }

        @TearDown(Level.Trial)
        public void teardownTrial() {
            world.close();
            arena.close();
        }
    }

    @Benchmark
    public void queryIteration_ReadWrite(QueryState state, Blackhole bh) {
        state.rwSys.bh = bh;
        state.rwSys.run();
        bh.consume(state.rwSys.count);
    }

    @Benchmark
    public void queryIteration_Update(QueryState state) {
        state.updSys.run();
    }

    @State(Scope.Thread)
    public static class HandleState {
        public ComponentManager manager;
        public Arena arena;
        public MemorySegment segment;
        public int POS_X, POS_Y;

        @Setup(Level.Trial)
        public void setup() {
            manager = new ComponentManager();
            manager.registerComponent(PositionComponent.class);
            POS_X = manager.getDescriptor(PositionComponent.class).getFieldIndex("x");
            POS_Y = manager.getDescriptor(PositionComponent.class).getFieldIndex("y");
            arena = Arena.ofConfined();
            segment = manager.allocate(PositionComponent.class, arena);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            arena.close();
        }
    }

    @Benchmark
    public void handlePooling_CreateRelease(HandleState state, Blackhole bh) {
        ComponentHandle h = state.manager.createHandle(PositionComponent.class, state.segment);
        h.setFloat(state.POS_X, 1f);
        h.setFloat(state.POS_Y, 2f);
        bh.consume(h.getFloat(state.POS_X));
        state.manager.releaseHandle(h);
    }

    @Benchmark
    public void handlePooling_BoundHandle(HandleState state, Blackhole bh) {
        try (ComponentManager.BoundHandle h = state.manager.acquireBoundHandle(PositionComponent.class, state.segment)) {
            h.handle().setFloat(state.POS_X, 1f);
            h.handle().setFloat(state.POS_Y, 2f);
            bh.consume(h.handle().getFloat(state.POS_X));
        }
    }

    @State(Scope.Thread)
    public static class MigrationState {
        public ComponentManager manager;
        public ArchetypeWorld world;
        public Arena arena;
        public SystemManager systemManager;
        public int[] entities;
        public int POS_X, POS_Y, VEL_VX, VEL_VY;
        public final int MIGRATION_COUNT = 10_000;

        @Setup(Level.Invocation) // Re-setup for each benchmark run
        public void setup() {
            manager = new ComponentManager();
            world = new ArchetypeWorld(manager);
            systemManager = new SystemManager(world);
            arena = Arena.ofConfined();
            world.registerComponent(PositionComponent.class);
            world.registerComponent(VelocityComponent.class);

            POS_X = manager.getDescriptor(PositionComponent.class).getFieldIndex("x");
            POS_Y = manager.getDescriptor(PositionComponent.class).getFieldIndex("y");
            VEL_VX = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vx");
            VEL_VY = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vy");

            entities = new int[MIGRATION_COUNT];
            for (int i = 0; i < MIGRATION_COUNT; i++) {
                entities[i] = world.createEntity();
                MemorySegment pos = manager.allocate(PositionComponent.class, arena);
                try(var h = manager.acquireBoundHandle(PositionComponent.class, pos)) {
                    h.handle().setFloat(POS_X, i);
                    h.handle().setFloat(POS_Y, i * 2);
                }
                world.addComponent(entities[i], PositionComponent.class, pos);
            }
        }

        @TearDown(Level.Invocation)
        public void teardown() {
            world.close();
            arena.close();
        }
    }

    @Benchmark
    public void archetypeMigration(MigrationState state) {
        for (int i = 0; i < state.MIGRATION_COUNT; i++) {
            MemorySegment vel = state.manager.allocate(VelocityComponent.class, state.arena);
            try(var h = state.manager.acquireBoundHandle(VelocityComponent.class, vel)) {
                h.handle().setFloat(state.VEL_VX, 1.0f);
                h.handle().setFloat(state.VEL_VY, -1.0f);
            }
            state.world.addComponent(state.entities[i], VelocityComponent.class, vel);
        }
    }

    // ========== Large Scale Benchmarks ==========

    @State(Scope.Thread)
    public static class LargeScaleState {
        @Param({"10", "100", "1000", "10000", "100000", "1000000"})
        public int entityCount;

        public ComponentManager manager;
        public ArchetypeWorld world;
        public Arena arena;
        public SystemManager systemManager;
        public int POS_X, POS_Y, VEL_VX, VEL_VY;
        public MovementSequentialSystem moveSeq;
        public MovementParallelSystem movePar;
        public RenderSystem render;

        @Setup(Level.Trial)
        public void setup() {
            manager = new ComponentManager();
            world = new ArchetypeWorld(manager);
            systemManager = new SystemManager(world);
            arena = Arena.ofConfined();

            world.registerComponent(PositionComponent.class);
            world.registerComponent(VelocityComponent.class);
            world.registerComponent(TransformComponent.class);
            world.registerComponent(HealthComponent.class);

            POS_X = manager.getDescriptor(PositionComponent.class).getFieldIndex("x");
            POS_Y = manager.getDescriptor(PositionComponent.class).getFieldIndex("y");
            VEL_VX = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vx");
            VEL_VY = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vy");

            // Create entities with different archetypes for realistic scenario
            for (int i = 0; i < entityCount; i++) {
                int e = world.createEntity();

                // All entities have position
                MemorySegment pos = manager.allocate(PositionComponent.class, arena);
                try(var h = manager.acquireBoundHandle(PositionComponent.class, pos)) {
                    h.handle().setFloat(POS_X, i * 0.1f);
                    h.handle().setFloat(POS_Y, i * 0.2f);
                }
                world.addComponent(e, PositionComponent.class, pos);

                // 70% have velocity
                if (i % 10 < 7) {
                    MemorySegment vel = manager.allocate(VelocityComponent.class, arena);
                    try(var h = manager.acquireBoundHandle(VelocityComponent.class, vel)) {
                        h.handle().setFloat(VEL_VX, (i % 3 - 1) * 0.5f);
                        h.handle().setFloat(VEL_VY, (i % 5 - 2) * 0.3f);
                    }
                    world.addComponent(e, VelocityComponent.class, vel);
                }

                // 30% have health
                if (i % 10 < 3) {
                    MemorySegment health = manager.allocate(HealthComponent.class, arena);
                    world.addComponent(e, HealthComponent.class, health);
                }
            }

            moveSeq = new MovementSequentialSystem();
            moveSeq.POS_X = POS_X; moveSeq.VEL_VX = VEL_VX;
            systemManager.registerSystem(moveSeq);

            movePar = new MovementParallelSystem();
            movePar.POS_X = POS_X; movePar.VEL_VX = VEL_VX;
            systemManager.registerSystem(movePar);

            render = new RenderSystem();
            render.POS_X = POS_X; render.POS_Y = POS_Y;
            systemManager.registerSystem(render);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            world.close();
            arena.close();
        }
    }

    @Benchmark
    public void largeScale_SequentialQuery(LargeScaleState state, Blackhole bh) {
        state.moveSeq.run();
        bh.consume(1); // keep BH engaged
    }

    @Benchmark
    public void largeScale_ParallelQuery(LargeScaleState state, Blackhole bh) {
        state.movePar.run();
        bh.consume(1);
    }

    @Benchmark
    public void largeScale_MultipleQueries(LargeScaleState state, Blackhole bh) {
        state.moveSeq.run();
        state.render.bh = bh;
        state.render.run();
        bh.consume(state.render.count);
    }

    @State(Scope.Thread)
    public static class MemoryPressureState {
        @Param({"1000", "10000", "100000"})
        public int entityCount;

        public ComponentManager manager;
        public ArchetypeWorld world;
        public Arena arena;
        public int POS_X, POS_Y;

        @Setup(Level.Invocation)
        public void setup() {
            manager = new ComponentManager();
            world = new ArchetypeWorld(manager);
            arena = Arena.ofConfined();

            world.registerComponent(PositionComponent.class);
            world.registerComponent(VelocityComponent.class);
            world.registerComponent(TransformComponent.class);

            POS_X = manager.getDescriptor(PositionComponent.class).getFieldIndex("x");
            POS_Y = manager.getDescriptor(PositionComponent.class).getFieldIndex("y");
        }

        @TearDown(Level.Invocation)
        public void teardown() {
            world.close();
            arena.close();
        }
    }

    @Benchmark
    public void memoryPressure_CreateAndDestroy(MemoryPressureState state) {
        // Create entities
        int[] entities = new int[state.entityCount];
        for (int i = 0; i < state.entityCount; i++) {
            entities[i] = state.world.createEntity();
            MemorySegment pos = state.manager.allocate(PositionComponent.class, state.arena);
            try(var h = state.manager.acquireBoundHandle(PositionComponent.class, pos)) {
                h.handle().setFloat(state.POS_X, i * 0.1f);
                h.handle().setFloat(state.POS_Y, i * 0.2f);
            }
            state.world.addComponent(entities[i], PositionComponent.class, pos);
        }

        // Destroy half
        for (int i = 0; i < state.entityCount / 2; i++) {
            state.world.destroyEntity(entities[i]);
        }
    }

    @State(Scope.Thread)
    public static class ThroughputState {
        @Param({"100000", "1000000"})
        public int entityCount;

        public ComponentManager manager;
        public ArchetypeWorld world;
        public Arena arena;
        public SystemManager systemManager;
        public int POS_X, POS_Y, VEL_VX, VEL_VY;
        public ThroughputPosReadSystem posRead;
        public EntitiesPerSecondSystem eps;

        @Setup(Level.Trial)
        public void setup() {
            manager = new ComponentManager();
            world = new ArchetypeWorld(manager);
            systemManager = new SystemManager(world);
            arena = Arena.ofConfined();

            world.registerComponent(PositionComponent.class);
            world.registerComponent(VelocityComponent.class);

            POS_X = manager.getDescriptor(PositionComponent.class).getFieldIndex("x");
            POS_Y = manager.getDescriptor(PositionComponent.class).getFieldIndex("y");
            VEL_VX = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vx");
            VEL_VY = manager.getDescriptor(VelocityComponent.class).getFieldIndex("vy");

            // Setup entities for throughput test
            for (int i = 0; i < entityCount; i++) {
                int e = world.createEntity();

                MemorySegment pos = manager.allocate(PositionComponent.class, arena);
                try(var h = manager.acquireBoundHandle(PositionComponent.class, pos)) {
                    h.handle().setFloat(POS_X, i * 0.1f);
                    h.handle().setFloat(POS_Y, i * 0.2f);
                }
                world.addComponent(e, PositionComponent.class, pos);

                MemorySegment vel = manager.allocate(VelocityComponent.class, arena);
                try(var h = manager.acquireBoundHandle(VelocityComponent.class, vel)) {
                    h.handle().setFloat(VEL_VX, 1.0f);
                    h.handle().setFloat(VEL_VY, -1.0f);
                }
                world.addComponent(e, VelocityComponent.class, vel);
            }

            posRead = new ThroughputPosReadSystem();
            posRead.POS_X = POS_X;
            systemManager.registerSystem(posRead);

            eps = new EntitiesPerSecondSystem();
            eps.POS_X = POS_X; eps.POS_Y = POS_Y; eps.VEL_VX = VEL_VX; eps.VEL_VY = VEL_VY;
            systemManager.registerSystem(eps);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            world.close();
            arena.close();
        }
    }

    @Benchmark
    public void throughput_MaximalIteration(ThroughputState state, Blackhole bh) {
        state.posRead.run();
        bh.consume(1);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void throughput_EntitiesPerSecond(ThroughputState state) {
        state.eps.run();
    }
}

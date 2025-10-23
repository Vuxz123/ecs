package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.components.PositionComponent;
import com.ethnicthv.ecs.components.VelocityComponent;
import com.ethnicthv.ecs.components.TransformComponent;
import com.ethnicthv.ecs.components.HealthComponent;
import com.ethnicthv.ecs.core.ComponentManager;
import com.ethnicthv.ecs.core.ComponentHandle;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Comprehensive benchmark for Component Manager + Archetype ECS system
 */
public class ComponentManagerBenchmark {

    private static final int WARM_UP_ITERATIONS = 3;
    private static final int BENCHMARK_ITERATIONS = 10;
    private static final int ENTITY_COUNT = 100_000;

    public static void main(String[] args) {
        System.out.println("=== Component Manager + Archetype ECS Benchmark ===\n");

        // Warm-up JVM
        System.out.println("Warming up JVM...");
        for (int i = 0; i < WARM_UP_ITERATIONS; i++) {
            runQuickBenchmark(1000);
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException e) {}
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Starting benchmarks with " + ENTITY_COUNT + " entities");
        System.out.println("=".repeat(60) + "\n");

        // Run benchmarks
        benchmarkComponentRegistration();
        benchmarkEntityCreation();
        benchmarkComponentAddition();
        benchmarkQueryIteration();
        benchmarkHandlePooling();
        benchmarkMemoryAccess();
        benchmarkArchetypeMigration();

        System.out.println("\n=== Benchmark Complete ===");
    }

    private static void runQuickBenchmark(int entityCount) {
        ComponentManager manager = new ComponentManager();
        ArchetypeWorld world = new ArchetypeWorld(manager);
        world.registerComponent(PositionComponent.class);
        world.registerComponent(VelocityComponent.class);

        try (Arena arena = Arena.ofConfined()) {
            for (int i = 0; i < entityCount; i++) {
                int e = world.createEntity();
                MemorySegment pos = manager.allocate(PositionComponent.class, arena);
                world.addComponent(e, PositionComponent.class, pos);
            }
            world.query().forEachEntityWith((id, handles, loc, arch) -> {
                handles[0].getFloat("x");
            }, PositionComponent.class);
        }
        world.close();
    }

    private static void benchmarkComponentRegistration() {
        System.out.println("📋 Benchmark: Component Registration");

        long[] times = new long[BENCHMARK_ITERATIONS];
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            ComponentManager manager = new ComponentManager();
            long start = System.nanoTime();

            manager.registerComponent(PositionComponent.class);
            manager.registerComponent(VelocityComponent.class);
            manager.registerComponent(TransformComponent.class);
            manager.registerComponent(HealthComponent.class);

            long end = System.nanoTime();
            times[i] = end - start;
        }

        printStats("Component Registration (4 types)", times, "µs");
    }

    private static void benchmarkEntityCreation() {
        System.out.println("\n🏗️  Benchmark: Entity Creation");

        long[] times = new long[BENCHMARK_ITERATIONS];
        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            ComponentManager manager = new ComponentManager();
            ArchetypeWorld world = new ArchetypeWorld(manager);
            world.registerComponent(PositionComponent.class);

            long start = System.nanoTime();
            for (int i = 0; i < ENTITY_COUNT; i++) {
                world.createEntity();
            }
            long end = System.nanoTime();
            times[iter] = end - start;
            world.close();
        }

        printStats("Entity Creation (" + ENTITY_COUNT + " entities)", times, "ms");
        printThroughput("Entities/sec", ENTITY_COUNT, times);
    }

    private static void benchmarkComponentAddition() {
        System.out.println("\n➕ Benchmark: Component Addition");

        long[] times = new long[BENCHMARK_ITERATIONS];
        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            ComponentManager manager = new ComponentManager();
            ArchetypeWorld world = new ArchetypeWorld(manager);
            world.registerComponent(PositionComponent.class);
            world.registerComponent(VelocityComponent.class);

            // Pre-create entities
            int[] entities = new int[ENTITY_COUNT];
            for (int i = 0; i < ENTITY_COUNT; i++) {
                entities[i] = world.createEntity();
            }

            try (Arena arena = Arena.ofConfined()) {
                long start = System.nanoTime();
                for (int i = 0; i < ENTITY_COUNT; i++) {
                    MemorySegment pos = manager.allocate(PositionComponent.class, arena);
                    ComponentHandle h = manager.createHandle(PositionComponent.class, pos);
                    h.setFloat("x", i);
                    h.setFloat("y", i * 2);
                    manager.releaseHandle(h);
                    world.addComponent(entities[i], PositionComponent.class, pos);
                }
                long end = System.nanoTime();
                times[iter] = end - start;
            }
            world.close();
        }

        printStats("Add Components (" + ENTITY_COUNT + " components)", times, "ms");
        printThroughput("Components/sec", ENTITY_COUNT, times);
    }

    private static void benchmarkQueryIteration() {
        System.out.println("\n🔍 Benchmark: Query Iteration");

        ComponentManager manager = new ComponentManager();
        ArchetypeWorld world = new ArchetypeWorld(manager);
        world.registerComponent(PositionComponent.class);
        world.registerComponent(VelocityComponent.class);

        // Setup entities
        try (Arena arena = Arena.ofConfined()) {
            for (int i = 0; i < ENTITY_COUNT; i++) {
                int e = world.createEntity();

                MemorySegment pos = manager.allocate(PositionComponent.class, arena);
                ComponentHandle ph = manager.createHandle(PositionComponent.class, pos);
                ph.setFloat("x", i);
                ph.setFloat("y", i * 2);
                manager.releaseHandle(ph);
                world.addComponent(e, PositionComponent.class, pos);

                if (i % 2 == 0) { // 50% have velocity
                    MemorySegment vel = manager.allocate(VelocityComponent.class, arena);
                    ComponentHandle vh = manager.createHandle(VelocityComponent.class, vel);
                    vh.setFloat("vx", 1.0f);
                    vh.setFloat("vy", -1.0f);
                    manager.releaseHandle(vh);
                    world.addComponent(e, VelocityComponent.class, vel);
                }
            }

            // Benchmark query iteration
            long[] times = new long[BENCHMARK_ITERATIONS];
            for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
                final int[] count = {0};
                long start = System.nanoTime();

                world.query().forEachEntityWith((entityId, handles, location, archetype) -> {
                    float x = handles[0].getFloat("x");
                    float y = handles[0].getFloat("y");
                    float vx = handles[1].getFloat("vx");
                    float vy = handles[1].getFloat("vy");
                    // Simple computation to prevent optimization
                    if (x + y + vx + vy > -1) count[0]++;
                }, PositionComponent.class, VelocityComponent.class);

                long end = System.nanoTime();
                times[iter] = end - start;
            }

            printStats("Query Iteration (" + (ENTITY_COUNT/2) + " matching entities)", times, "ms");
            printThroughput("Entities/sec", ENTITY_COUNT/2, times);
        }
        world.close();
    }

    private static void benchmarkHandlePooling() {
        System.out.println("\n🔄 Benchmark: Handle Pooling");

        ComponentManager manager = new ComponentManager();
        manager.registerComponent(PositionComponent.class);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = manager.allocate(PositionComponent.class, arena);

            // Benchmark with pooling
            long[] timesPooled = new long[BENCHMARK_ITERATIONS];
            for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
                long start = System.nanoTime();
                for (int i = 0; i < 10_000; i++) {
                    ComponentHandle h = manager.createHandle(PositionComponent.class, segment);
                    h.setFloat("x", i);
                    h.setFloat("y", i * 2);
                    float x = h.getFloat("x");
                    manager.releaseHandle(h);
                }
                long end = System.nanoTime();
                timesPooled[iter] = end - start;
            }

            printStats("Handle Pooling (10k acquire/release)", timesPooled, "ms");

            // Benchmark BoundHandle (AutoCloseable)
            long[] timesBound = new long[BENCHMARK_ITERATIONS];
            for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
                long start = System.nanoTime();
                for (int i = 0; i < 10_000; i++) {
                    try (ComponentManager.BoundHandle bh = manager.acquireBoundHandle(PositionComponent.class, segment)) {
                        bh.handle().setFloat("x", i);
                        bh.handle().setFloat("y", i * 2);
                        float x = bh.handle().getFloat("x");
                    }
                }
                long end = System.nanoTime();
                timesBound[iter] = end - start;
            }

            printStats("BoundHandle (10k try-with-resources)", timesBound, "ms");
        }
    }

    private static void benchmarkMemoryAccess() {
        System.out.println("\n💾 Benchmark: Memory Access Patterns");

        ComponentManager manager = new ComponentManager();
        ArchetypeWorld world = new ArchetypeWorld(manager);
        world.registerComponent(PositionComponent.class);
        world.registerComponent(VelocityComponent.class);

        // Create entities with components
        try (Arena arena = Arena.ofConfined()) {
            for (int i = 0; i < ENTITY_COUNT; i++) {
                int e = world.createEntity();

                MemorySegment pos = manager.allocate(PositionComponent.class, arena);
                ComponentHandle ph = manager.createHandle(PositionComponent.class, pos);
                ph.setFloat("x", i);
                ph.setFloat("y", i * 2);
                manager.releaseHandle(ph);
                world.addComponent(e, PositionComponent.class, pos);

                MemorySegment vel = manager.allocate(VelocityComponent.class, arena);
                ComponentHandle vh = manager.createHandle(VelocityComponent.class, vel);
                vh.setFloat("vx", 1.0f);
                vh.setFloat("vy", -1.0f);
                manager.releaseHandle(vh);
                world.addComponent(e, VelocityComponent.class, vel);
            }

            // Benchmark: Read-only access
            long[] timesRead = new long[BENCHMARK_ITERATIONS];
            for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
                long start = System.nanoTime();
                world.query().forEachEntityWith((entityId, handles, location, archetype) -> {
                    float x = handles[0].getFloat("x");
                    float y = handles[0].getFloat("y");
                }, PositionComponent.class);
                long end = System.nanoTime();
                timesRead[iter] = end - start;
            }
            printStats("Read-only Access (" + ENTITY_COUNT + " entities)", timesRead, "ms");

            // Benchmark: Write access
            long[] timesWrite = new long[BENCHMARK_ITERATIONS];
            for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
                long start = System.nanoTime();
                world.query().forEachEntityWith((entityId, handles, location, archetype) -> {
                    float x = handles[0].getFloat("x");
                    float y = handles[0].getFloat("y");
                    handles[0].setFloat("x", x + 1.0f);
                    handles[0].setFloat("y", y + 1.0f);
                }, PositionComponent.class);
                long end = System.nanoTime();
                timesWrite[iter] = end - start;
            }
            printStats("Read-Write Access (" + ENTITY_COUNT + " entities)", timesWrite, "ms");

            // Benchmark: Multi-component access
            long[] timesMulti = new long[BENCHMARK_ITERATIONS];
            for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
                long start = System.nanoTime();
                world.query().forEachEntityWith((entityId, handles, location, archetype) -> {
                    float x = handles[0].getFloat("x");
                    float vx = handles[1].getFloat("vx");
                    handles[0].setFloat("x", x + vx);
                }, PositionComponent.class, VelocityComponent.class);
                long end = System.nanoTime();
                timesMulti[iter] = end - start;
            }
            printStats("Multi-component Update (" + ENTITY_COUNT + " entities)", timesMulti, "ms");
        }
        world.close();
    }

    private static void benchmarkArchetypeMigration() {
        System.out.println("\n🔄 Benchmark: Archetype Migration");

        long[] times = new long[BENCHMARK_ITERATIONS];
        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            ComponentManager manager = new ComponentManager();
            ArchetypeWorld world = new ArchetypeWorld(manager);
            world.registerComponent(PositionComponent.class);
            world.registerComponent(VelocityComponent.class);

            // Create entities with only Position
            int[] entities = new int[10_000];
            try (Arena arena = Arena.ofConfined()) {
                for (int i = 0; i < 10_000; i++) {
                    entities[i] = world.createEntity();
                    MemorySegment pos = manager.allocate(PositionComponent.class, arena);
                    ComponentHandle h = manager.createHandle(PositionComponent.class, pos);
                    h.setFloat("x", i);
                    h.setFloat("y", i * 2);
                    manager.releaseHandle(h);
                    world.addComponent(entities[i], PositionComponent.class, pos);
                }

                // Benchmark: Add velocity to all (causes archetype migration)
                long start = System.nanoTime();
                for (int i = 0; i < 10_000; i++) {
                    MemorySegment vel = manager.allocate(VelocityComponent.class, arena);
                    ComponentHandle h = manager.createHandle(VelocityComponent.class, vel);
                    h.setFloat("vx", 1.0f);
                    h.setFloat("vy", -1.0f);
                    manager.releaseHandle(h);
                    world.addComponent(entities[i], VelocityComponent.class, vel);
                }
                long end = System.nanoTime();
                times[iter] = end - start;
            }
            world.close();
        }

        printStats("Archetype Migration (10k entities)", times, "ms");
        printThroughput("Migrations/sec", 10_000, times);
    }

    // Utility methods for statistics
    private static void printStats(String name, long[] timesNanos, String unit) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long sum = 0;

        for (long time : timesNanos) {
            min = Math.min(min, time);
            max = Math.max(max, time);
            sum += time;
        }

        double avg = (double) sum / timesNanos.length;

        // Convert to requested unit
        double divisor = switch (unit) {
            case "µs" -> 1_000.0;
            case "ms" -> 1_000_000.0;
            case "s" -> 1_000_000_000.0;
            default -> 1.0;
        };

        System.out.printf("  %-50s Min: %8.2f %s | Avg: %8.2f %s | Max: %8.2f %s\n",
            name,
            min / divisor, unit,
            avg / divisor, unit,
            max / divisor, unit
        );
    }

    private static void printThroughput(String name, int operations, long[] timesNanos) {
        long sum = 0;
        for (long time : timesNanos) {
            sum += time;
        }
        double avgSeconds = (double) sum / timesNanos.length / 1_000_000_000.0;
        double throughput = operations / avgSeconds;

        System.out.printf("  %-50s %,.0f\n", name + ":", throughput);
    }
}


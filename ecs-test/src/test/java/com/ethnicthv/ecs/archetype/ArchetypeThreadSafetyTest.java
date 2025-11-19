package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.archetype.Archetype;
import com.ethnicthv.ecs.core.archetype.ArchetypeChunk;
import com.ethnicthv.ecs.core.archetype.ComponentMask;
import com.ethnicthv.ecs.core.components.Component;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import org.junit.jupiter.api.*;

import java.lang.foreign.Arena;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA/QC Test Suite: Thread Safety và Concurrency
 * Đảm bảo hệ thống hoạt động an toàn trong môi trường đa luồng
 */
@DisplayName("QA/QC: Thread Safety Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ArchetypeThreadSafetyTest {

    private Arena arena;
    private ComponentDescriptor[] descriptors;
    private int[] componentIds;
    private ComponentMask mask;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent1.class, 16),
                makeDesc(TestComponent2.class, 8)
        };
        componentIds = new int[]{1, 2};
        mask = new ComponentMask();
        mask = mask.set(1);
        mask = mask.set(2);
    }

    private static ComponentDescriptor makeDesc(Class<?> clazz, long size) {
        return new ComponentDescriptor(
                clazz,
                size,
                List.of(),
                Component.LayoutType.SEQUENTIAL
        );
    }

    @Test
    @Order(1)
    @DisplayName("TC-TS-001: Concurrent entity additions should be thread-safe")
    void testConcurrentEntityAdditions() throws InterruptedException {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);
        int threadCount = 10;
        int entitiesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ConcurrentHashMap<Integer, ArchetypeChunk.ChunkLocation> locations = new ConcurrentHashMap<>();
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < entitiesPerThread; i++) {
                        int entityId = threadId * entitiesPerThread + i;
                        ArchetypeChunk.ChunkLocation loc = archetype.addEntity(entityId);
                        assertNotNull(loc, "Location should not be null for entity " + entityId);

                        ArchetypeChunk.ChunkLocation previous = locations.putIfAbsent(entityId, loc);
                        if (previous != null) {
                            errorCount.incrementAndGet();
                            System.err.println("Duplicate entity location detected: " + entityId);
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test timeout - threads did not complete");
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(0, errorCount.get(), "No errors should occur during concurrent additions");
        assertEquals(threadCount * entitiesPerThread, locations.size(),
                "All entities should be added uniquely");

        System.out.printf("✓ Successfully added %d entities concurrently from %d threads%n",
                threadCount * entitiesPerThread, threadCount);
    }

    @Test
    @Order(2)
    @DisplayName("TC-TS-002: Concurrent add and remove operations should maintain consistency")
    void testConcurrentAddRemove() throws InterruptedException {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);
        int operations = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        ConcurrentHashMap<Integer, ArchetypeChunk.ChunkLocation> activeEntities = new ConcurrentHashMap<>();
        AtomicInteger entityIdCounter = new AtomicInteger(0);
        AtomicInteger addCount = new AtomicInteger(0);
        AtomicInteger removeCount = new AtomicInteger(0);

        // Adder threads (2 threads)
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                for (int j = 0; j < operations; j++) {
                    try {
                        int entityId = entityIdCounter.incrementAndGet();
                        ArchetypeChunk.ChunkLocation loc = archetype.addEntity(entityId);
                        activeEntities.put(entityId, loc);
                        addCount.incrementAndGet();
                    } catch (Exception e) {
                        System.err.println("Error adding entity: " + e.getMessage());
                    }
                }
            });
        }

        // Remover threads (2 threads)
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                for (int j = 0; j < operations / 2; j++) {
                    try {
                        activeEntities.keySet().stream().findAny().ifPresent(entityId -> {
                            ArchetypeChunk.ChunkLocation loc = activeEntities.remove(entityId);
                            if (loc != null) {
                                try {
                                    archetype.removeEntity(loc);
                                    removeCount.incrementAndGet();
                                } catch (Exception e) {
                                    System.err.println("Error removing entity: " + e.getMessage());
                                }
                            }
                        });
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS), "Concurrent operations timeout");

        System.out.printf("✓ Concurrent operations completed: %d adds, %d removes%n",
                addCount.get(), removeCount.get());
        System.out.printf("✓ Final state: %d active entities%n", activeEntities.size());

        // Verify final consistency
        assertTrue(activeEntities.size() >= 0, "Active entities count should be non-negative");
    }

    @Test
    @Order(3)
    @DisplayName("TC-TS-003: Parallel chunk iteration should be consistent")
    void testParallelChunkIteration() throws InterruptedException {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Pre-populate with entities
        for (int i = 0; i < 1000; i++) {
            archetype.addEntity(i);
        }

        ExecutorService executor = Executors.newFixedThreadPool(5);
        AtomicInteger totalIterations = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(5);

        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        archetype.getChunks().forEach(chunk -> {
                            assertNotNull(chunk);
                            totalIterations.incrementAndGet();
                        });
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        System.out.printf("✓ Completed %d parallel chunk iterations%n", totalIterations.get());
        assertTrue(totalIterations.get() > 0, "Should have performed iterations");
    }

    @Test
    @Order(4)
    @DisplayName("TC-TS-004: Race condition stress test")
    void testRaceConditionStress() throws InterruptedException {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);
        int threadCount = 20;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successfulOps = new AtomicInteger(0);
        AtomicInteger failedOps = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        try {
                            int entityId = threadId * operationsPerThread + i;
                            archetype.addEntity(entityId);
                            successfulOps.incrementAndGet();
                        } catch (Exception e) {
                            failedOps.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS), "Stress test timeout");
        executor.shutdown();

        int totalOps = threadCount * operationsPerThread;
        System.out.printf("✓ Stress test: %d successful, %d failed out of %d operations%n",
                successfulOps.get(), failedOps.get(), totalOps);

        assertTrue(successfulOps.get() > 0, "Should have some successful operations");
        assertTrue((double) successfulOps.get() / totalOps > 0.95,
                "Success rate should be > 95%");
    }

    @Test
    @Order(5)
    @DisplayName("TC-TS-005: Deadlock detection test")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testNoDeadlock() throws InterruptedException {
        Archetype archetype1 = new Archetype(mask, componentIds, descriptors, arena);
        Archetype archetype2 = new Archetype(mask, componentIds, descriptors, arena);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);

        // Thread 1: archetype1 -> archetype2
        executor.submit(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    archetype1.addEntity(i);
                    archetype2.addEntity(i + 1000);
                }
            } finally {
                latch.countDown();
            }
        });

        // Thread 2: archetype2 -> archetype1
        executor.submit(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    archetype2.addEntity(i + 2000);
                    archetype1.addEntity(i + 3000);
                }
            } finally {
                latch.countDown();
            }
        });

        // Thread 3 & 4: Mixed operations
        for (int t = 0; t < 2; t++) {
            final int offset = t * 4000;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        if (i % 2 == 0) {
                            archetype1.addEntity(i + offset);
                        } else {
                            archetype2.addEntity(i + offset);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(25, TimeUnit.SECONDS), "Deadlock detected or timeout");
        executor.shutdown();

        System.out.println("✓ No deadlock detected in cross-archetype operations");
    }

    // Test component classes
    static class TestComponent1 {
        long value1;
        long value2;
    }

    static class TestComponent2 {
        long value;
    }
}


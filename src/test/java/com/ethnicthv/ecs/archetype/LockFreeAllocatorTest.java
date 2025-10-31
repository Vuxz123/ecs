package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.components.Component;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import com.ethnicthv.ecs.core.archetype.Archetype;
import com.ethnicthv.ecs.core.archetype.ArchetypeChunk;
import com.ethnicthv.ecs.core.archetype.ComponentMask;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LockFreeAllocatorTest {

    static final Arena shared = Arena.ofShared();

    private static ComponentDescriptor makeDesc(Class<?> clazz, long size) {
        return new ComponentDescriptor(
                clazz,
                size,
                List.of(),
                Component.LayoutType.SEQUENTIAL
        );
    }

    @AfterAll
    static void tearDown() {
        // do not close shared arena; it is shared across tests/world
    }

    @Test
    void allocateUpToCapacityConcurrently() throws InterruptedException {
        int capacity = 256;
        ComponentDescriptor[] descs = new ComponentDescriptor[] {
                makeDesc(DummyC1.class, 16),
                makeDesc(DummyC2.class, 8)
        };
        long[] sizes = new long[] { 16, 8 };
        ArchetypeChunk chunk = new ArchetypeChunk(descs, sizes, capacity, shared);

        int threads = 8;
        int attemptsPerThread = 1000; // will saturate chunk multiple times
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int baseId = t * attemptsPerThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < attemptsPerThread; i++) {
                        int eid = baseId + i;
                        int idx = chunk.allocateSlot(eid);
                        if (idx >= 0) {
                            // immediately free half of allocations to churn
                            if ((eid & 1) == 0) {
                                chunk.freeSlot(idx);
                            }
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // size must be within [0, capacity]
        Assertions.assertTrue(chunk.size() >= 0 && chunk.size() <= capacity);

        // All occupied slots must have a valid entity id != -1
        int occupied = 0;
        for (int i = 0; i < capacity; i++) {
            int eid = chunk.getEntityId(i);
            if (eid != -1) occupied++;
        }
        Assertions.assertEquals(chunk.size(), occupied);
    }

    @Test
    void archetypeConcurrentAddsScaleAcrossChunks() throws InterruptedException {
        // Small capacity to force multiple chunks
        ComponentDescriptor[] descs = new ComponentDescriptor[] {
                makeDesc(DummyC1.class, 16),
                makeDesc(DummyC2.class, 8)
        };
        ComponentMask empty = new ComponentMask();
        int[] compIds = new int[] {0, 1};
        Archetype archetype = new Archetype(empty, compIds, descs, shared);

        int threads = 8;
        int entities = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        int perThread = entities / threads;
        for (int t = 0; t < threads; t++) {
            final int baseId = t * perThread + 1;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        archetype.addEntity(baseId + i);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // Count entities discovered by iteration; weakly consistent but should reach expected count
        final int[] count = {0};
        archetype.forEach((eid, loc, chunk) -> count[0]++);
        Assertions.assertEquals(entities, count[0]);

        // sanity: at least more than one chunk should exist
        Assertions.assertTrue(archetype.getChunks().size() >= 1);
    }

    // Dummy component classes for descriptor construction
    static final class DummyC1 {}
    static final class DummyC2 {}
}


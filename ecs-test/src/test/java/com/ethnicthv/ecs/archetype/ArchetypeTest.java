package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.archetype.Archetype;
import com.ethnicthv.ecs.core.archetype.ArchetypeChunk;
import com.ethnicthv.ecs.core.archetype.ComponentMask;
import com.ethnicthv.ecs.core.api.archetype.IArchetypeChunk;
import com.ethnicthv.ecs.core.components.Component;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Archetype class
 */
public class ArchetypeTest {

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
        mask.set(1);
        mask.set(2);
    }

    @AfterEach
    void tearDown() {
        // Don't close shared arena
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
    void testArchetypeCreation() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        assertNotNull(archetype);
        assertEquals(mask, archetype.getMask());
        assertArrayEquals(componentIds, archetype.getComponentIds());
        assertArrayEquals(componentIds, archetype.getComponentTypeIds());
        assertArrayEquals(descriptors, archetype.getDescriptors());
    }

    @Test
    void testGetChunks() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        List<IArchetypeChunk> chunks = archetype.getChunks();
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty(), "Should have at least one initial chunk");
    }

    @Test
    void testAddSingleEntity() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        ArchetypeChunk.ChunkLocation loc = archetype.addEntity(100);
        assertNotNull(loc);
        assertTrue(loc.chunkIndex >= 0);
        assertTrue(loc.indexInChunk >= 0);
    }

    @Test
    void testAddMultipleEntities() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        for (int i = 0; i < 10; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(100 + i);
            assertNotNull(loc);
            assertTrue(loc.chunkIndex >= 0);
            assertTrue(loc.indexInChunk >= 0);
        }

        // Verify entities can be iterated
        AtomicInteger count = new AtomicInteger(0);
        archetype.forEach((eid, loc, chunk) -> count.incrementAndGet());
        assertEquals(10, count.get());
    }

    @Test
    void testRemoveEntity() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Add entity
        ArchetypeChunk.ChunkLocation loc = archetype.addEntity(100);
        assertNotNull(loc);

        // Remove entity
        archetype.removeEntity(loc);

        // Verify entity is gone
        AtomicInteger count = new AtomicInteger(0);
        archetype.forEach((eid, location, chunk) -> count.incrementAndGet());
        assertEquals(0, count.get());
    }

    @Test
    void testRemoveNonExistentEntity() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        ArchetypeChunk.ChunkLocation loc = new ArchetypeChunk.ChunkLocation(0, 0);
        // Should handle gracefully without throwing
        assertDoesNotThrow(() -> archetype.removeEntity(loc));
    }

    @Test
    void testForEachIteration() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Add multiple entities
        int entityCount = 20;
        for (int i = 0; i < entityCount; i++) {
            archetype.addEntity(100 + i);
        }

        // Count via forEach
        AtomicInteger iteratedCount = new AtomicInteger(0);
        archetype.forEach((eid, loc, chunk) -> {
            assertTrue(eid >= 100 && eid < 100 + entityCount);
            assertNotNull(loc);
            assertNotNull(chunk);
            iteratedCount.incrementAndGet();
        });

        assertEquals(entityCount, iteratedCount.get());
    }

    @Test
    void testEntitiesPerChunk() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        int entitiesPerChunk = archetype.getEntitiesPerChunk();
        assertTrue(entitiesPerChunk > 0, "Entities per chunk should be positive");
    }

    @Test
    void testMultipleChunks() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        int entitiesPerChunk = archetype.getEntitiesPerChunk();
        int totalEntities = entitiesPerChunk * 2 + 10; // Ensure we span multiple chunks

        for (int i = 0; i < totalEntities; i++) {
            archetype.addEntity(1000 + i);
        }

        List<IArchetypeChunk> chunks = archetype.getChunks();
        assertTrue(chunks.size() >= 2, "Should have at least 2 chunks");

        // Verify iteration covers all entities
        AtomicInteger count = new AtomicInteger(0);
        archetype.forEach((eid, loc, chunk) -> count.incrementAndGet());
        assertEquals(totalEntities, count.get());
    }

    @Test
    void testConcurrentAddEntity() throws InterruptedException {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        int threads = 8;
        int entitiesPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int baseId = t * entitiesPerThread + 1;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < entitiesPerThread; i++) {
                            archetype.addEntity(baseId + i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Timed out waiting for concurrent adds");
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate in time");
        }

        // Count entities via iteration
        AtomicInteger count = new AtomicInteger(0);
        archetype.forEach((eid, loc, chunk) -> count.incrementAndGet());
        assertEquals(threads * entitiesPerThread, count.get());
    }

    @Test
    void testConcurrentAddAndRemove() throws InterruptedException {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        int threads = 4;
        int operations = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int baseId = t * operations + 1;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operations; i++) {
                            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(baseId + i);
                            // Remove half of them
                            if (i % 2 == 0) {
                                archetype.removeEntity(loc);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Timed out waiting for concurrent add/remove");
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate in time");
        }

        // Count remaining entities
        AtomicInteger count = new AtomicInteger(0);
        archetype.forEach((eid, loc, chunk) -> count.incrementAndGet());

        // Should have roughly half of total operations
        int expected = threads * operations / 2;
        assertTrue(count.get() > 0 && count.get() <= expected,
                "Count " + count.get() + " should be between 0 and " + expected);
    }

    @Test
    void testEmptyArchetypeIteration() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        AtomicInteger count = new AtomicInteger(0);
        archetype.forEach((eid, loc, chunk) -> count.incrementAndGet());
        assertEquals(0, count.get(), "Empty archetype should not iterate");
    }

    @Test
    void testGetComponentData() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        ArchetypeChunk.ChunkLocation loc = archetype.addEntity(100);
        assertNotNull(loc);

        // Get component data
        var data = archetype.getComponentData(loc, 0);
        assertNotNull(data);
    }

    @Test
    void testSetComponentData() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        ArchetypeChunk.ChunkLocation loc = archetype.addEntity(100);
        assertNotNull(loc);

        // Create test data
        var testData = arena.allocate(16);
        testData.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, 42L);

        // Set component data
        archetype.setComponentData(loc, 0, testData);

        // Verify
        var retrieved = archetype.getComponentData(loc, 0);
        assertEquals(42L, retrieved.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0));
    }

    @Test
    void testIndexOfComponentType() {
        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Component ID 1 should be at index 0
        int index1 = archetype.indexOfComponentType(1);
        assertEquals(0, index1);

        // Component ID 2 should be at index 1
        int index2 = archetype.indexOfComponentType(2);
        assertEquals(1, index2);

        // Non-existent component should return -1
        int indexInvalid = archetype.indexOfComponentType(999);
        assertEquals(-1, indexInvalid);
    }

    @Test
    void testMismatchedComponentIdsAndDescriptors() {
        ComponentDescriptor[] mismatchedDescs = new ComponentDescriptor[]{
                makeDesc(TestComponent1.class, 16)
        };
        int[] mismatchedIds = new int[]{1, 2}; // More IDs than descriptors

        assertThrows(IllegalArgumentException.class, () ->
                new Archetype(mask, mismatchedIds, mismatchedDescs, arena)
        );
    }

    @Test
    void testZeroSizeComponents() {
        ComponentDescriptor[] zeroSizeDescs = new ComponentDescriptor[]{
                makeDesc(TestComponent1.class, 0),
                makeDesc(TestComponent2.class, 0)
        };

        // Zero-size components should throw IllegalArgumentException when allocating chunks
        assertThrows(IllegalArgumentException.class, () -> {
            Archetype archetype = new Archetype(mask, componentIds, zeroSizeDescs, arena);
            // Attempting to add an entity will trigger chunk creation with zero-size components
            archetype.addEntity(1);
        });
    }

    // Test component classes
    static final class TestComponent1 {
    }

    static final class TestComponent2 {
    }
}


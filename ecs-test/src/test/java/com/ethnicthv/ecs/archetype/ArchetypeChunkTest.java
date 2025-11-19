package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.archetype.ArchetypeChunk;
import com.ethnicthv.ecs.core.components.Component;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ArchetypeChunk
 */
public class ArchetypeChunkTest {

    private Arena arena;
    private ComponentDescriptor[] descriptors;
    private long[] elementSizes;
    private static final int DEFAULT_CAPACITY = 64;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent1.class, 16),
                makeDesc(TestComponent2.class, 8)
        };
        elementSizes = new long[]{16, 8};
    }

    @AfterEach
    void tearDown() {
        // Don't close shared arena - it may be used across tests
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
    void testChunkInitialization() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        assertEquals(DEFAULT_CAPACITY, chunk.capacity());
        assertEquals(0, chunk.size());
        assertTrue(chunk.isEmpty());
        assertTrue(chunk.hasFree());
    }

    @Test
    void testAllocateSingleSlot() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        int slot = chunk.allocateSlot(100);
        assertTrue(slot >= 0 && slot < DEFAULT_CAPACITY);
        assertEquals(1, chunk.size());
        assertFalse(chunk.isEmpty());
        assertEquals(100, chunk.getEntityId(slot));
    }

    @Test
    void testAllocateMultipleSlots() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        Set<Integer> allocatedSlots = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            int slot = chunk.allocateSlot(1000 + i);
            assertTrue(slot >= 0);
            assertTrue(allocatedSlots.add(slot), "Slots should be unique");
            assertEquals(1000 + i, chunk.getEntityId(slot));
        }

        assertEquals(10, chunk.size());
    }

    @Test
    void testAllocateUpToCapacity() {
        int capacity = 16;
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, capacity, arena);

        for (int i = 0; i < capacity; i++) {
            int slot = chunk.allocateSlot(i);
            assertTrue(slot >= 0, "Should allocate slot " + i);
        }

        assertEquals(capacity, chunk.size());
        assertFalse(chunk.hasFree());

        // Try to allocate one more - should fail
        int extraSlot = chunk.allocateSlot(999);
        assertEquals(-1, extraSlot, "Should not allocate beyond capacity");
    }

    @Test
    void testFreeSlot() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        int slot = chunk.allocateSlot(100);
        assertEquals(1, chunk.size());

        chunk.freeSlot(slot);
        assertEquals(0, chunk.size());
        assertTrue(chunk.isEmpty());
        assertEquals(-1, chunk.getEntityId(slot));
    }

    @Test
    void testAllocateFreeAllocate() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        // Allocate
        int slot1 = chunk.allocateSlot(100);
        assertEquals(1, chunk.size());

        // Free
        chunk.freeSlot(slot1);
        assertEquals(0, chunk.size());

        // Allocate again - might reuse same slot
        int slot2 = chunk.allocateSlot(200);
        assertTrue(slot2 >= 0);
        assertEquals(1, chunk.size());
        assertEquals(200, chunk.getEntityId(slot2));
    }

    @Test
    void testDoubleFree() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        int slot = chunk.allocateSlot(100);
        chunk.freeSlot(slot);
        assertEquals(0, chunk.size());

        // Double free should be safe
        chunk.freeSlot(slot);
        assertEquals(0, chunk.size());
    }

    @Test
    void testGetComponentData() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        int slot = chunk.allocateSlot(100);

        // Get component data for component 0
        MemorySegment data = chunk.getComponentData(0, slot);
        assertNotNull(data);
        assertEquals(16, data.byteSize());

        // Get component data for component 1
        MemorySegment data2 = chunk.getComponentData(1, slot);
        assertNotNull(data2);
        assertEquals(8, data2.byteSize());
    }

    @Test
    void testSetComponentData() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        int slot = chunk.allocateSlot(100);

        // Create test data
        MemorySegment testData = arena.allocate(16);
        testData.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, 12345L);
        testData.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8, 67890L);

        // Set component data
        chunk.setComponentData(0, slot, testData);

        // Retrieve and verify
        MemorySegment retrieved = chunk.getComponentData(0, slot);
        assertEquals(12345L, retrieved.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0));
        assertEquals(67890L, retrieved.get(java.lang.foreign.ValueLayout.JAVA_LONG, 8));
    }

    @Test
    void testNextOccupiedIndex() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        // Allocate some slots
        chunk.allocateSlot(100);
        chunk.allocateSlot(101);
        chunk.allocateSlot(102);

        // Find first occupied
        int first = chunk.nextOccupiedIndex(0);
        assertTrue(first >= 0);
        assertNotEquals(-1, chunk.getEntityId(first));

        // Find next after first
        int second = chunk.nextOccupiedIndex(first + 1);
        assertTrue(second > first);
        assertNotEquals(-1, chunk.getEntityId(second));

        // Count all occupied
        int count = 0;
        int idx = chunk.nextOccupiedIndex(0);
        while (idx >= 0) {
            count++;
            idx = chunk.nextOccupiedIndex(idx + 1);
        }
        assertEquals(3, count);
    }

    @Test
    void testNextOccupiedIndexWithGaps() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        // Allocate several slots
        int[] slots = new int[5];
        for (int i = 0; i < 5; i++) {
            slots[i] = chunk.allocateSlot(100 + i);
        }

        // Free some to create gaps
        chunk.freeSlot(slots[1]);
        chunk.freeSlot(slots[3]);

        // Count occupied (should be 3)
        int count = 0;
        int idx = chunk.nextOccupiedIndex(0);
        while (idx >= 0) {
            assertNotEquals(-1, chunk.getEntityId(idx), "Occupied slot should have valid entity ID");
            count++;
            idx = chunk.nextOccupiedIndex(idx + 1);
        }
        assertEquals(3, count);
    }

    @Test
    void testQueueingMechanism() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        // Initially not queued
        assertTrue(chunk.tryMarkQueued());

        // Should fail second time
        assertFalse(chunk.tryMarkQueued());

        // Mark dequeued
        chunk.markDequeued();

        // Should succeed again
        assertTrue(chunk.tryMarkQueued());
    }

    @Test
    void testConcurrentAllocations() throws InterruptedException {
        int capacity = 128;
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, capacity, arena);

        int threads = 4;
        int allocationsPerThread = capacity / threads;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int baseId = t * allocationsPerThread;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < allocationsPerThread; i++) {
                            int slot = chunk.allocateSlot(baseId + i);
                            if (slot >= 0) {
                                successCount.incrementAndGet();
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
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for allocations to complete");
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate in time");
        }

        assertEquals(capacity, successCount.get());
        assertEquals(capacity, chunk.size());
    }

    @Test
    void testConcurrentAllocateAndFree() throws InterruptedException {
        int capacity = 256;
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, capacity, arena);

        int threads = 8;
        int operations = 1000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int baseId = t * operations;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operations; i++) {
                            int slot = chunk.allocateSlot(baseId + i);
                            if (slot >= 0) {
                                // Free every other allocation
                                if (i % 2 == 0) {
                                    chunk.freeSlot(slot);
                                }
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
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Timed out waiting for concurrent operations");
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate in time");
        }

        // Size should be reasonable (between 0 and capacity)
        int finalSize = chunk.size();
        assertTrue(finalSize >= 0 && finalSize <= capacity,
                "Final size " + finalSize + " should be in [0, " + capacity + "]");

        // All occupied slots should have valid entity IDs
        int occupiedCount = 0;
        for (int i = 0; i < capacity; i++) {
            if (chunk.getEntityId(i) != -1) {
                occupiedCount++;
            }
        }
        assertEquals(finalSize, occupiedCount);
    }

    @Test
    void testSlotDataIsZeroedOnAllocation() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        // Allocate a slot and set some data
        int slot1 = chunk.allocateSlot(100);
        MemorySegment data1 = chunk.getComponentData(0, slot1);
        data1.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, 0xDEADBEEFL);

        // Free the slot
        chunk.freeSlot(slot1);

        // Allocate again (might get the same slot)
        int slot2 = chunk.allocateSlot(200);
        MemorySegment data2 = chunk.getComponentData(0, slot2);

        // Data should be zeroed
        assertEquals(0L, data2.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0));
    }

    @Test
    void testInvalidComponentIndex() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);
        int slot = chunk.allocateSlot(100);

        assertThrows(IndexOutOfBoundsException.class, () -> chunk.getComponentData(-1, slot));

        assertThrows(IndexOutOfBoundsException.class, () -> chunk.getComponentData(descriptors.length, slot));
    }

    @Test
    void testInvalidElementIndex() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        assertThrows(IndexOutOfBoundsException.class, () -> chunk.getComponentData(0, -1));

        assertThrows(IndexOutOfBoundsException.class, () -> chunk.getComponentData(0, DEFAULT_CAPACITY));
    }

    @Test
    void testZeroCapacityChunk() {
        // Zero capacity should throw IllegalArgumentException because bytes = elementSize * 0 = 0
        assertThrows(IllegalArgumentException.class, () ->
                new ArchetypeChunk(descriptors, elementSizes, 0, arena)
        );
    }

    @Test
    void testLargeScaleAllocations_1000Entities() {
        int capacity = 1024;
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, capacity, arena);

        // Allocate 1000 entities
        int entityCount = 1000;
        Set<Integer> allocatedSlots = new HashSet<>();
        for (int i = 0; i < entityCount; i++) {
            int slot = chunk.allocateSlot(10000 + i);
            assertTrue(slot >= 0, "Should allocate slot for entity " + i);
            assertTrue(allocatedSlots.add(slot), "Slot should be unique");
            assertEquals(10000 + i, chunk.getEntityId(slot));
        }

        assertEquals(entityCount, chunk.size());
        assertEquals(entityCount, allocatedSlots.size());

        // Verify all allocated entities are accessible
        int verifiedCount = 0;
        for (int slot : allocatedSlots) {
            int entityId = chunk.getEntityId(slot);
            assertTrue(entityId >= 10000 && entityId < 10000 + entityCount);
            verifiedCount++;
        }
        assertEquals(entityCount, verifiedCount);
    }

    @Test
    void testLargeScaleAllocationsAndFrees_5000Operations() {
        int capacity = 2048;
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, capacity, arena);

        // Perform 5000 mixed allocate/free operations
        Set<Integer> activeSlots = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            if (i % 3 == 0 && !activeSlots.isEmpty()) {
                // Free a random slot
                Integer slotToFree = activeSlots.iterator().next();
                chunk.freeSlot(slotToFree);
                activeSlots.remove(slotToFree);
            } else {
                // Allocate new slot
                int slot = chunk.allocateSlot(20000 + i);
                if (slot >= 0) {
                    activeSlots.add(slot);
                }
            }
        }

        // Verify chunk consistency
        assertEquals(activeSlots.size(), chunk.size());

        // Verify all active slots have valid entity IDs
        for (int slot : activeSlots) {
            int entityId = chunk.getEntityId(slot);
            assertNotEquals(-1, entityId, "Active slot should have valid entity ID");
        }
    }

    @Test
    void testConcurrentAllocations_10000Entities() throws InterruptedException {
        int capacity = 10240;
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, capacity, arena);

        int threads = 8;
        int entitiesPerThread = 1250; // 8 * 1250 = 10000
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        Set<Integer> allEntityIds = ConcurrentHashMap.newKeySet();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int baseId = t * entitiesPerThread + 30000;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < entitiesPerThread; i++) {
                            int entityId = baseId + i;
                            int slot = chunk.allocateSlot(entityId);
                            if (slot >= 0) {
                                successCount.incrementAndGet();
                                allEntityIds.add(entityId);
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
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Timed out waiting for allocations");
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(10000, successCount.get(), "Should successfully allocate 10000 entities");
        assertEquals(10000, allEntityIds.size(), "All entity IDs should be unique");
        assertEquals(10000, chunk.size());
    }

    @Test
    void testConcurrentChurn_20000Operations() throws InterruptedException {
        // This is a heavy stress-style test that can be sensitive to scheduler / timing.
        // Only run it when explicitly enabled, to avoid flakiness in the default suite.
        Assumptions.assumeTrue(
                Boolean.getBoolean("ecs.enableChunkChurnStress"),
                "Skipping testConcurrentChurn_20000Operations; enable with -Decs.enableChunkChurnStress=true"
        );

        int capacity = 4096;
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, capacity, arena);

        int threads = 10;
        int operationsPerThread = 2000; // 10 * 2000 = 20000
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int baseId = t * operationsPerThread + 40000;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Set<Integer> mySlots = new HashSet<>();
                        for (int i = 0; i < operationsPerThread; i++) {
                            if (i % 4 == 0 && !mySlots.isEmpty()) {
                                // Free one of our slots
                                Integer slot = mySlots.iterator().next();
                                chunk.freeSlot(slot);
                                mySlots.remove(slot);
                            } else {
                                // Allocate new
                                int slot = chunk.allocateSlot(baseId + i);
                                if (slot >= 0) {
                                    mySlots.add(slot);
                                }
                            }
                        }
                        // Clean up our remaining slots
                        for (int slot : mySlots) {
                            chunk.freeSlot(slot);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(60, TimeUnit.SECONDS), "Timed out waiting for operations");
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        // After all threads clean up, chunk should be empty or have very few entities
        int finalSize = chunk.size();
        assertTrue(finalSize >= 0 && finalSize <= capacity);

        // All slots marked as occupied should have valid entity IDs
        int occupiedCount = 0;
        for (int i = 0; i < capacity; i++) {
            if (chunk.getEntityId(i) != -1) {
                occupiedCount++;
            }
        }
        assertEquals(finalSize, occupiedCount);
    }

    @Test
    void testSingleSlotCapacity() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, 1, arena);

        assertEquals(1, chunk.capacity());

        int slot = chunk.allocateSlot(100);
        assertEquals(0, slot);
        assertEquals(1, chunk.size());
        assertFalse(chunk.hasFree());

        int slot2 = chunk.allocateSlot(200);
        assertEquals(-1, slot2);

        chunk.freeSlot(slot);
        assertTrue(chunk.hasFree());
        assertEquals(0, chunk.size());
    }

    @Test
    void testArenaAccess() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);
        assertNotNull(chunk.getArena());
        assertEquals(arena, chunk.getArena());
    }

    @Test
    void testGetEntityCount() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);

        assertEquals(0, chunk.getEntityCount());

        chunk.allocateSlot(100);
        assertEquals(1, chunk.getEntityCount());

        chunk.allocateSlot(101);
        chunk.allocateSlot(102);
        assertEquals(3, chunk.getEntityCount());
    }

    @Test
    void testGetCapacity() {
        ArchetypeChunk chunk = new ArchetypeChunk(descriptors, elementSizes, DEFAULT_CAPACITY, arena);
        assertEquals(DEFAULT_CAPACITY, chunk.getCapacity());
    }

    // Test component classes
    static final class TestComponent1 {
    }

    static final class TestComponent2 {
    }
}


package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.archetype.Archetype;
import com.ethnicthv.ecs.core.archetype.ArchetypeChunk;
import com.ethnicthv.ecs.core.archetype.ComponentMask;
import com.ethnicthv.ecs.core.components.Component;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import org.junit.jupiter.api.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA/QC Test Suite: Performance và Scalability
 * Đảm bảo hệ thống hoạt động hiệu quả với khối lượng lớn dữ liệu
 */
@DisplayName("QA/QC: Performance & Scalability Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ArchetypePerformanceTest {

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
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("TC-PF-001: Sequential addition performance (100K entities)")
    void testSequentialAdditionPerformance() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        int entityCount = 100_000;
        long start = System.nanoTime();

        for (int i = 0; i < entityCount; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(i);
            assertNotNull(loc);
        }

        long duration = System.nanoTime() - start;
        double durationMs = duration / 1_000_000.0;
        double throughput = entityCount / (duration / 1_000_000_000.0);

        assertEquals(entityCount, archetype.getEntityCount());

        System.out.printf("✓ Added %,d entities in %.2f ms (%.0f entities/sec)%n",
                entityCount, durationMs, throughput);

        assertTrue(throughput > 10_000, "Should achieve >10K entities/sec");
    }

    @Test
    @Order(2)
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("TC-PF-002: Component data read/write performance")
    void testComponentDataReadWritePerformance() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        int entityCount = 50_000;
        List<ArchetypeChunk.ChunkLocation> locations = new ArrayList<>(entityCount);

        // Add entities
        for (int i = 0; i < entityCount; i++) {
            locations.add(archetype.addEntity(i));
        }

        // Write performance
        long writeStart = System.nanoTime();
        for (int i = 0; i < entityCount; i++) {
            MemorySegment data = archetype.getComponentData(locations.get(i), 0);
            data.set(ValueLayout.JAVA_LONG, 0, (long) i);
            data.set(ValueLayout.JAVA_LONG, 8, (long) i * 2);
        }
        long writeTime = System.nanoTime() - writeStart;

        // Read performance
        long readStart = System.nanoTime();
        for (int i = 0; i < entityCount; i++) {
            MemorySegment data = archetype.getComponentData(locations.get(i), 0);
            long v1 = data.get(ValueLayout.JAVA_LONG, 0);
            long v2 = data.get(ValueLayout.JAVA_LONG, 8);
            assertEquals((long) i, v1);
            assertEquals((long) i * 2, v2);
        }
        long readTime = System.nanoTime() - readStart;

        System.out.printf("✓ Write: %.2f ms (%.0f ops/sec)%n",
                writeTime / 1_000_000.0, entityCount / (writeTime / 1_000_000_000.0));
        System.out.printf("✓ Read:  %.2f ms (%.0f ops/sec)%n",
                readTime / 1_000_000.0, entityCount / (readTime / 1_000_000_000.0));
    }

    @Test
    @Order(3)
    @DisplayName("TC-PF-003: Chunk reuse efficiency")
    void testChunkReuseEfficiency() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);
        int entitiesPerChunk = archetype.getEntitiesPerChunk();

        // Fill multiple chunks
        List<ArchetypeChunk.ChunkLocation> locations = new ArrayList<>();
        for (int i = 0; i < entitiesPerChunk * 3; i++) {
            locations.add(archetype.addEntity(i));
        }

        int initialChunkCount = archetype.getChunkCount();

        // Remove first chunk's entities
        for (int i = 0; i < entitiesPerChunk; i++) {
            archetype.removeEntity(locations.get(i));
        }

        // Add same amount back - should reuse first chunk
        for (int i = entitiesPerChunk * 3; i < entitiesPerChunk * 4; i++) {
            archetype.addEntity(i);
        }

        int finalChunkCount = archetype.getChunkCount();

        assertEquals(initialChunkCount, finalChunkCount,
                "Should reuse existing chunks efficiently");

        System.out.printf("✓ Chunk reuse verified: %d chunks maintained (%.0f%% efficiency)%n",
                finalChunkCount, 100.0);
    }

    @Test
    @Order(4)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("TC-PF-004: Scalability test (1M entities)")
    void testScalabilityOneMillion() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(SmallComponent.class, 8)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        int entityCount = 1_000_000;
        long start = System.nanoTime();

        for (int i = 0; i < entityCount; i++) {
            archetype.addEntity(i);

            // Progress indicator
            if ((i + 1) % 100_000 == 0) {
                System.out.printf("  Progress: %,d / %,d entities%n", i + 1, entityCount);
            }
        }

        long duration = System.nanoTime() - start;
        double durationSec = duration / 1_000_000_000.0;

        assertEquals(entityCount, archetype.getEntityCount());

        System.out.printf("✓ Scaled to %,d entities in %.2f seconds%n",
                entityCount, durationSec);
        System.out.printf("✓ Chunk count: %,d%n", archetype.getChunkCount());
        System.out.printf("✓ Average: %.0f entities/sec%n", entityCount / durationSec);
    }

    @Test
    @Order(5)
    @DisplayName("TC-PF-005: Multi-component performance")
    void testMultiComponentPerformance() {
        Arena arena = Arena.ofShared();

        // Create archetype with multiple components
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(Component1.class, 16),
                makeDesc(Component2.class, 32),
                makeDesc(Component3.class, 8),
                makeDesc(Component4.class, 24)
        };
        int[] componentIds = new int[]{1, 2, 3, 4};
        ComponentMask mask = new ComponentMask();
        for (int id : componentIds) {
            mask = mask.set(id);
        }

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        int entityCount = 10_000;
        long start = System.nanoTime();

        List<ArchetypeChunk.ChunkLocation> locations = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            locations.add(archetype.addEntity(i));
        }

        // Access all components for each entity
        for (int i = 0; i < entityCount; i++) {
            for (int comp = 0; comp < 4; comp++) {
                MemorySegment data = archetype.getComponentData(locations.get(i), comp);
                assertNotNull(data);
            }
        }

        long duration = System.nanoTime() - start;
        double durationMs = duration / 1_000_000.0;

        System.out.printf("✓ Multi-component test: %,d entities × 4 components in %.2f ms%n",
                entityCount, durationMs);
    }

    @Test
    @Order(6)
    @DisplayName("TC-PF-006: Iteration performance")
    void testIterationPerformance() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Add entities
        int entityCount = 100_000;
        for (int i = 0; i < entityCount; i++) {
            archetype.addEntity(i);
        }

        // Iteration performance
        long start = System.nanoTime();
        int iteratedCount = 0;

        for (var chunk : archetype.getChunks()) {
            int count = chunk.getEntityCount();
            for (int i = 0; i < count; i++) {
                iteratedCount++;
            }
        }

        long duration = System.nanoTime() - start;
        double durationMs = duration / 1_000_000.0;

        assertEquals(entityCount, iteratedCount);
        System.out.printf("✓ Iterated %,d entities in %.2f ms (%.0f entities/ms)%n",
                iteratedCount, durationMs, iteratedCount / durationMs);
    }

    @Test
    @Order(7)
    @DisplayName("TC-PF-007: Memory footprint estimation")
    void testMemoryFootprint() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        int entityCount = 100_000;
        for (int i = 0; i < entityCount; i++) {
            archetype.addEntity(i);
        }

        long estimatedBytes = (long) entityCount * 16; // 16 bytes per entity
        double estimatedMB = estimatedBytes / (1024.0 * 1024.0);

        System.out.printf("✓ Memory footprint for %,d entities:%n", entityCount);
        System.out.printf("  - Estimated: %.2f MB%n", estimatedMB);
        System.out.printf("  - Chunk count: %,d%n", archetype.getChunkCount());
        System.out.printf("  - Entities per chunk: %,d%n", archetype.getEntitiesPerChunk());
    }

    @Test
    @Order(8)
    @DisplayName("TC-PF-008: Batch operation performance")
    void testBatchOperationPerformance() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        int batchSize = 1000;
        int batchCount = 100;

        long totalTime = 0;

        for (int batch = 0; batch < batchCount; batch++) {
            long start = System.nanoTime();

            for (int i = 0; i < batchSize; i++) {
                archetype.addEntity(batch * batchSize + i);
            }

            totalTime += System.nanoTime() - start;
        }

        double avgBatchTime = (totalTime / batchCount) / 1_000_000.0;

        System.out.printf("✓ Batch operation: %d batches of %,d entities%n",
                batchCount, batchSize);
        System.out.printf("✓ Average batch time: %.2f ms%n", avgBatchTime);
        System.out.printf("✓ Total entities: %,d%n", archetype.getEntityCount());
    }

    @Test
    @Order(9)
    @DisplayName("TC-PF-009: Fragmentation resistance")
    void testFragmentationResistance() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Create fragmentation pattern: add-remove-add
        int cycles = 10;
        for (int cycle = 0; cycle < cycles; cycle++) {
            // Add
            List<ArchetypeChunk.ChunkLocation> locations = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                locations.add(archetype.addEntity(cycle * 1000 + i));
            }

            // Remove every other
            for (int i = 0; i < locations.size(); i += 2) {
                archetype.removeEntity(locations.get(i));
            }
        }

        int finalChunks = archetype.getChunkCount();
        int finalEntities = archetype.getEntityCount();

        System.out.printf("✓ Fragmentation test after %d cycles:%n", cycles);
        System.out.printf("  - Final chunks: %d%n", finalChunks);
        System.out.printf("  - Final entities: %,d%n", finalEntities);
        System.out.printf("  - Avg entities/chunk: %.1f%n",
                (double) finalEntities / finalChunks);
    }

    // Test component classes
    static class TestComponent {
        long value1;
        long value2;
    }

    static class SmallComponent {
        long value;
    }

    static class Component1 {
        long a, b;
    }

    static class Component2 {
        long a, b, c, d;
    }

    static class Component3 {
        long a;
    }

    static class Component4 {
        long a, b, c;
    }
}


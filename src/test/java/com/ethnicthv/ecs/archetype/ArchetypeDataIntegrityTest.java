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

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA/QC Test Suite: Data Integrity và Consistency
 * Đảm bảo tính toàn vẹn dữ liệu trong mọi trường hợp
 */
@DisplayName("QA/QC: Data Integrity Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ArchetypeDataIntegrityTest {

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
    @DisplayName("TC-DI-001: Data persistence across operations")
    void testDataPersistence() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Add entities with specific data
        List<ArchetypeChunk.ChunkLocation> locations = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(i);
            locations.add(loc);

            MemorySegment data = archetype.getComponentData(loc, 0);
            data.set(ValueLayout.JAVA_LONG, 0, (long) i * 1000);
            data.set(ValueLayout.JAVA_LONG, 8, (long) i * 2000);
        }

        // Verify all data persists
        for (int i = 0; i < 100; i++) {
            MemorySegment data = archetype.getComponentData(locations.get(i), 0);
            long value1 = data.get(ValueLayout.JAVA_LONG, 0);
            long value2 = data.get(ValueLayout.JAVA_LONG, 8);

            assertEquals((long) i * 1000, value1,
                    "First value corrupted for entity " + i);
            assertEquals((long) i * 2000, value2,
                    "Second value corrupted for entity " + i);
        }

        System.out.println("✓ Data persistence verified for 100 entities");
    }

    @Test
    @Order(2)
    @DisplayName("TC-DI-002: Data isolation between entities")
    void testDataIsolationBetweenEntities() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Add entities with unique values
        List<ArchetypeChunk.ChunkLocation> locations = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(i);
            locations.add(loc);

            MemorySegment data = archetype.getComponentData(loc, 0);
            data.set(ValueLayout.JAVA_LONG, 0, (long) i);
            data.set(ValueLayout.JAVA_LONG, 8, (long) i * 10);
        }

        // Modify every other entity
        for (int i = 0; i < 50; i += 2) {
            MemorySegment data = archetype.getComponentData(locations.get(i), 0);
            data.set(ValueLayout.JAVA_LONG, 0, 99999L);
        }

        // Verify unmodified entities are intact
        for (int i = 1; i < 50; i += 2) {
            MemorySegment data = archetype.getComponentData(locations.get(i), 0);
            long value1 = data.get(ValueLayout.JAVA_LONG, 0);
            long value2 = data.get(ValueLayout.JAVA_LONG, 8);

            assertEquals((long) i, value1, "Entity " + i + " was corrupted");
            assertEquals((long) i * 10, value2, "Entity " + i + " was corrupted");
        }

        System.out.println("✓ Data isolation verified between entities");
    }

    @Test
    @Order(3)
    @DisplayName("TC-DI-003: Data consistency after removal")
    void testDataConsistencyAfterRemoval() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Add entities
        List<ArchetypeChunk.ChunkLocation> locations = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(i);
            locations.add(loc);

            MemorySegment data = archetype.getComponentData(loc, 0);
            data.set(ValueLayout.JAVA_LONG, 0, (long) i);
        }

        // Remove middle entities
        for (int i = 25; i < 75; i++) {
            archetype.removeEntity(locations.get(i));
        }

        // Verify remaining entities' data
        for (int i = 0; i < 25; i++) {
            MemorySegment data = archetype.getComponentData(locations.get(i), 0);
            long value = data.get(ValueLayout.JAVA_LONG, 0);
            assertEquals((long) i, value, "Entity " + i + " corrupted after removal");
        }

        for (int i = 75; i < 100; i++) {
            MemorySegment data = archetype.getComponentData(locations.get(i), 0);
            long value = data.get(ValueLayout.JAVA_LONG, 0);
            assertEquals((long) i, value, "Entity " + i + " corrupted after removal");
        }

        System.out.println("✓ Data consistency maintained after entity removal");
    }

    @Test
    @Order(4)
    @DisplayName("TC-DI-004: Multi-component data consistency")
    void testMultiComponentDataConsistency() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(Component1.class, 8),
                makeDesc(Component2.class, 16),
                makeDesc(Component3.class, 32)
        };
        int[] componentIds = new int[]{1, 2, 3};
        ComponentMask mask = new ComponentMask();
        for (int id : componentIds) {
            mask = mask.set(id);
        }

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Add entities with data in all components
        List<ArchetypeChunk.ChunkLocation> locations = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(i);
            locations.add(loc);

            MemorySegment data1 = archetype.getComponentData(loc, 0);
            MemorySegment data2 = archetype.getComponentData(loc, 1);
            MemorySegment data3 = archetype.getComponentData(loc, 2);

            data1.set(ValueLayout.JAVA_LONG, 0, (long) i * 1);
            data2.set(ValueLayout.JAVA_LONG, 0, (long) i * 2);
            data2.set(ValueLayout.JAVA_LONG, 8, (long) i * 3);
            data3.set(ValueLayout.JAVA_LONG, 0, (long) i * 4);
            data3.set(ValueLayout.JAVA_LONG, 8, (long) i * 5);
            data3.set(ValueLayout.JAVA_LONG, 16, (long) i * 6);
            data3.set(ValueLayout.JAVA_LONG, 24, (long) i * 7);
        }

        // Verify all components maintain data correctly
        for (int i = 0; i < 50; i++) {
            MemorySegment data1 = archetype.getComponentData(locations.get(i), 0);
            MemorySegment data2 = archetype.getComponentData(locations.get(i), 1);
            MemorySegment data3 = archetype.getComponentData(locations.get(i), 2);

            assertEquals((long) i * 1, data1.get(ValueLayout.JAVA_LONG, 0));
            assertEquals((long) i * 2, data2.get(ValueLayout.JAVA_LONG, 0));
            assertEquals((long) i * 3, data2.get(ValueLayout.JAVA_LONG, 8));
            assertEquals((long) i * 4, data3.get(ValueLayout.JAVA_LONG, 0));
            assertEquals((long) i * 5, data3.get(ValueLayout.JAVA_LONG, 8));
            assertEquals((long) i * 6, data3.get(ValueLayout.JAVA_LONG, 16));
            assertEquals((long) i * 7, data3.get(ValueLayout.JAVA_LONG, 24));
        }

        System.out.println("✓ Multi-component data consistency verified");
    }

    @Test
    @Order(5)
    @DisplayName("TC-DI-005: Cross-chunk data integrity")
    void testCrossChunkDataIntegrity() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);
        int entitiesPerChunk = archetype.getEntitiesPerChunk();

        // Add entities across multiple chunks
        List<ArchetypeChunk.ChunkLocation> locations = new ArrayList<>();
        int totalEntities = entitiesPerChunk * 3 + 50; // 3+ chunks

        for (int i = 0; i < totalEntities; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(i);
            locations.add(loc);

            MemorySegment data = archetype.getComponentData(loc, 0);
            data.set(ValueLayout.JAVA_LONG, 0, (long) i);
            data.set(ValueLayout.JAVA_LONG, 8, (long) i * 100);
        }

        assertTrue(archetype.getChunkCount() >= 3, "Should span multiple chunks");

        // Verify data across all chunks
        for (int i = 0; i < totalEntities; i++) {
            MemorySegment data = archetype.getComponentData(locations.get(i), 0);
            long value1 = data.get(ValueLayout.JAVA_LONG, 0);
            long value2 = data.get(ValueLayout.JAVA_LONG, 8);

            assertEquals((long) i, value1, "Cross-chunk data corrupted at " + i);
            assertEquals((long) i * 100, value2, "Cross-chunk data corrupted at " + i);
        }

        System.out.printf("✓ Cross-chunk integrity verified across %d chunks%n",
                archetype.getChunkCount());
    }

    @Test
    @Order(6)
    @DisplayName("TC-DI-006: Archetype mask integrity")
    void testArchetypeMaskIntegrity() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16),
                makeDesc(TestComponent.class, 8)
        };
        int[] componentIds = new int[]{5, 10};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(5);
        mask = mask.set(10);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Add many entities
        for (int i = 0; i < 1000; i++) {
            archetype.addEntity(i);
        }

        // Verify mask remains consistent
        ComponentMask retrievedMask = archetype.getMask();
        assertTrue(retrievedMask.has(5), "Component 5 should be set");
        assertTrue(retrievedMask.has(10), "Component 10 should be set");
        assertFalse(retrievedMask.has(1), "Component 1 should not be set");

        // Verify component IDs
        assertArrayEquals(componentIds, archetype.getComponentIds());

        System.out.println("✓ Archetype mask integrity maintained");
    }

    @Test
    @Order(7)
    @DisplayName("TC-DI-007: Entity count accuracy")
    void testEntityCountAccuracy() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        assertEquals(0, archetype.getEntityCount(), "Initial count should be 0");

        // Add entities
        List<ArchetypeChunk.ChunkLocation> locations = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            locations.add(archetype.addEntity(i));
            assertEquals(i + 1, archetype.getEntityCount(),
                    "Count should match after adding entity " + i);
        }

        // Remove entities
        for (int i = 0; i < 50; i++) {
            archetype.removeEntity(locations.get(i));
            assertEquals(100 - i - 1, archetype.getEntityCount(),
                    "Count should match after removing entity " + i);
        }

        System.out.println("✓ Entity count accuracy verified through operations");
    }

    @Test
    @Order(8)
    @DisplayName("TC-DI-008: No data corruption under stress")
    void testNoDataCorruptionUnderStress() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        int testSize = 1000;
        List<ArchetypeChunk.ChunkLocation> locations = new ArrayList<>();

        // Add entities with checksums
        for (int i = 0; i < testSize; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(i);
            locations.add(loc);

            MemorySegment data = archetype.getComponentData(loc, 0);
            long checksum = (long) i * 12345 + 67890;
            data.set(ValueLayout.JAVA_LONG, 0, checksum);
            data.set(ValueLayout.JAVA_LONG, 8, ~checksum); // Inverted checksum
        }

        // Perform random operations
        for (int i = 0; i < 100; i++) {
            int idx = i % testSize;
            MemorySegment data = archetype.getComponentData(locations.get(idx), 0);
            data.set(ValueLayout.JAVA_LONG, 0, data.get(ValueLayout.JAVA_LONG, 0) + 1);
        }

        // Verify checksums
        int corruptedCount = 0;
        for (int i = 0; i < testSize; i++) {
            MemorySegment data = archetype.getComponentData(locations.get(i), 0);
            long value1 = data.get(ValueLayout.JAVA_LONG, 0);
            long value2 = data.get(ValueLayout.JAVA_LONG, 8);

            long expectedBase = (long) i * 12345 + 67890;

            // Account for modifications
            if (i < 100 && value1 != expectedBase + 1) {
                corruptedCount++;
            } else if (i >= 100 && value1 != expectedBase) {
                corruptedCount++;
            }
        }

        assertEquals(0, corruptedCount, "No data should be corrupted");
        System.out.println("✓ No corruption detected under stress testing");
    }

    // Test component classes
    static class TestComponent {
        long value1;
        long value2;
    }

    static class Component1 {
        long value;
    }

    static class Component2 {
        long value1, value2;
    }

    static class Component3 {
        long value1, value2, value3, value4;
    }
}


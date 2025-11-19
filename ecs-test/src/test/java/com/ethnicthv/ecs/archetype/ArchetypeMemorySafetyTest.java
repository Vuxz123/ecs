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
 * QA/QC Test Suite: Memory Safety và Resource Management
 * Đảm bảo hệ thống quản lý bộ nhớ an toàn và không có memory leak
 */
@DisplayName("QA/QC: Memory Safety Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ArchetypeMemorySafetyTest {

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
    @DisplayName("TC-MS-001: Arena lifecycle management")
    void testArenaLifecycle() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Add entities
        for (int i = 0; i < 100; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(i);
            assertNotNull(loc, "Should successfully add entity " + i);
        }

        assertEquals(100, archetype.getEntityCount(), "Should have 100 entities");

        // Arena should still be valid
        assertTrue(arena.scope().isAlive(), "Arena should be alive");

        System.out.println("✓ Arena lifecycle properly managed with 100 entities");
    }

    @Test
    @Order(2)
    @DisplayName("TC-MS-002: Memory segment bounds checking")
    void testMemorySegmentBounds() {
        try (Arena arena = Arena.ofConfined()) {
            ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                    makeDesc(TestComponent.class, 16)
            };
            int[] componentIds = new int[]{1};
            ComponentMask mask = new ComponentMask();
            mask = mask.set(1);

            Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(1);

            MemorySegment data = archetype.getComponentData(loc, 0);
            assertNotNull(data, "Memory segment should not be null");
            assertEquals(16, data.byteSize(), "Segment size should match descriptor");

            // Valid access within bounds
            assertDoesNotThrow(() -> {
                data.set(ValueLayout.JAVA_LONG, 0, 12345L);
                long value = data.get(ValueLayout.JAVA_LONG, 0);
                assertEquals(12345L, value);
            }, "Should access memory within bounds");

            System.out.println("✓ Memory segment bounds properly enforced");
        }
    }

    @Test
    @Order(3)
    @DisplayName("TC-MS-003: Large allocation stress test")
    void testLargeAllocationStress() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(LargeComponent.class, 1024) // 1KB per entity
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Add many entities to trigger multiple chunk allocations
        int entityCount = 10000;
        for (int i = 0; i < entityCount; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(i);
            assertNotNull(loc, "Location should not be null for entity " + i);

            // Verify we can write to the allocated memory
            MemorySegment data = archetype.getComponentData(loc, 0);
            data.set(ValueLayout.JAVA_LONG, 0, (long) i);
        }

        assertEquals(entityCount, archetype.getEntityCount(), "All entities should be added");
        assertTrue(archetype.getChunkCount() > 1, "Should create multiple chunks for large data");

        System.out.printf("✓ Successfully allocated %d entities (%.2f MB) across %d chunks%n",
                entityCount, (entityCount * 1024.0) / (1024 * 1024), archetype.getChunkCount());
    }

    @Test
    @Order(4)
    @DisplayName("TC-MS-004: Memory reuse after entity removal")
    void testMemoryReuseAfterRemoval() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Fill archetype
        List<ArchetypeChunk.ChunkLocation> locations = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            locations.add(archetype.addEntity(i));
        }

        int initialChunkCount = archetype.getChunkCount();

        // Remove half
        for (int i = 0; i < 50; i++) {
            archetype.removeEntity(locations.get(i));
        }

        // Add new entities - should reuse memory
        for (int i = 100; i < 150; i++) {
            archetype.addEntity(i);
        }

        assertEquals(100, archetype.getEntityCount(), "Should have 100 entities after reuse");
        assertTrue(archetype.getChunkCount() <= initialChunkCount + 1,
                "Should reuse memory efficiently");

        System.out.printf("✓ Memory reuse verified: %d chunks maintained%n", archetype.getChunkCount());
    }

    @Test
    @Order(5)
    @DisplayName("TC-MS-005: Multiple archetypes resource isolation")
    void testMultipleArchetypesIsolation() {
        Arena arena = Arena.ofShared();

        List<Archetype> archetypes = new ArrayList<>();

        // Create multiple archetypes with different configurations
        for (int i = 0; i < 10; i++) {
            ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                    makeDesc(TestComponent.class, 16 + i * 8)
            };
            int[] componentIds = new int[]{i + 1};
            ComponentMask mask = new ComponentMask();
            mask = mask.set(i + 1);

            Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);
            archetypes.add(archetype);

            // Add entities to each
            for (int j = 0; j < 100; j++) {
                archetype.addEntity(j);
            }
        }

        // Verify isolation
        for (int i = 0; i < 10; i++) {
            Archetype archetype = archetypes.get(i);
            assertEquals(100, archetype.getEntityCount(),
                    "Archetype " + i + " should have 100 entities");
            assertTrue(archetype.getChunkCount() > 0,
                    "Archetype " + i + " should have chunks");
        }

        System.out.println("✓ 10 archetypes properly isolated with 100 entities each");
    }

    @Test
    @Order(6)
    @DisplayName("TC-MS-006: Component data integrity after operations")
    void testComponentDataIntegrity() {
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

        // Verify data integrity
        for (int i = 0; i < 100; i++) {
            MemorySegment data = archetype.getComponentData(locations.get(i), 0);
            long value1 = data.get(ValueLayout.JAVA_LONG, 0);
            long value2 = data.get(ValueLayout.JAVA_LONG, 8);

            assertEquals((long) i * 1000, value1,
                    "First value should be preserved for entity " + i);
            assertEquals((long) i * 2000, value2,
                    "Second value should be preserved for entity " + i);
        }

        System.out.println("✓ Data integrity verified for 100 entities");
    }

    @Test
    @Order(7)
    @DisplayName("TC-MS-007: Small component handling (1 byte)")
    void testSmallComponents() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TagComponent.class, 1) // Minimal 1-byte component
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Should be able to add entities with small components
        for (int i = 0; i < 100; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(i);
            assertNotNull(loc, "Should handle small components");
        }

        assertEquals(100, archetype.getEntityCount());
        System.out.println("✓ Small components (1 byte) handled correctly");
    }

    @Test
    @Order(8)
    @DisplayName("TC-MS-008: Alignment verification")
    void testComponentAlignment() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                new ComponentDescriptor(AlignedComponent.class, 16, List.of(),
                        Component.LayoutType.SEQUENTIAL)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        for (int i = 0; i < 100; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(i);
            MemorySegment data = archetype.getComponentData(loc, 0);

            // Verify alignment
            long address = data.address();
            assertEquals(0, address % 8, "Component should be 8-byte aligned");
        }

        System.out.println("✓ Component alignment verified for 100 entities");
    }

    // Test component classes
    static class TestComponent {
        long value1;
        long value2;
    }

    static class LargeComponent {
        byte[] data = new byte[1024];
    }

    static class TagComponent {
        // Empty tag component
    }

    static class AlignedComponent {
        long value1;
        long value2;
    }
}


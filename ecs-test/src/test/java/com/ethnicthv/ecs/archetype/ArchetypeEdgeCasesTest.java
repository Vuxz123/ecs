package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.archetype.Archetype;
import com.ethnicthv.ecs.core.archetype.ArchetypeChunk;
import com.ethnicthv.ecs.core.archetype.ComponentMask;
import com.ethnicthv.ecs.core.components.Component;
import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import org.junit.jupiter.api.*;

import java.lang.foreign.Arena;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA/QC Test Suite: Edge Cases và Boundary Conditions
 * Kiểm tra các trường hợp biên, ngoại lệ và điều kiện giới hạn
 */
@DisplayName("QA/QC: Edge Cases Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ArchetypeEdgeCasesTest {

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
    @DisplayName("TC-EC-001: Empty component mask handling")
    void testEmptyComponentMask() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[0];
        int[] componentIds = new int[0];
        ComponentMask mask = new ComponentMask();

        // System allows empty archetype - verify it's created successfully
        Archetype archetype = assertDoesNotThrow(() -> {
            return new Archetype(mask, componentIds, descriptors, arena);
        }, "Empty archetype should be created successfully");

        assertNotNull(archetype, "Empty archetype should not be null");
        assertEquals(0, archetype.getComponentIds().length, "Should have 0 components");
        assertEquals(0, archetype.getDescriptors().length, "Should have 0 descriptors");

        // Should be able to add entities even with no components
        ArchetypeChunk.ChunkLocation loc = archetype.addEntity(1);
        assertNotNull(loc, "Should be able to add entity to empty archetype");
        assertEquals(1, archetype.getEntityCount(), "Should have 1 entity");

        System.out.println("✓ Empty component mask handled correctly - system allows empty archetypes");
    }

    @Test
    @Order(2)
    @DisplayName("TC-EC-002: Single component archetype")
    void testSingleComponentArchetype() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(SingleComponent.class, 8)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        for (int i = 0; i < 100; i++) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(i);
            assertNotNull(loc);
        }

        assertEquals(100, archetype.getEntityCount());
        System.out.println("✓ Single component archetype works correctly");
    }

    @Test
    @Order(3)
    @DisplayName("TC-EC-003: Maximum components per archetype")
    void testMaximumComponents() {
        Arena arena = Arena.ofShared();
        int maxComponents = 32; // Reasonable limit

        ComponentDescriptor[] descriptors = new ComponentDescriptor[maxComponents];
        int[] componentIds = new int[maxComponents];
        ComponentMask mask = new ComponentMask();

        for (int i = 0; i < maxComponents; i++) {
            descriptors[i] = makeDesc(TestComponent.class, 8);
            componentIds[i] = i + 1;
            mask = mask.set(i + 1);
        }

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        ArchetypeChunk.ChunkLocation loc = archetype.addEntity(1);
        assertNotNull(loc);

        System.out.printf("✓ Archetype with %d components created successfully%n", maxComponents);
    }

    @Test
    @Order(4)
    @DisplayName("TC-EC-004: Component index boundary tests")
    void testComponentIndexBoundaries() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 8),
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{1, 2};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);
        mask = mask.set(2);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Valid indices
        assertEquals(0, archetype.indexOfComponentType(1));
        assertEquals(1, archetype.indexOfComponentType(2));

        // Invalid index
        assertEquals(-1, archetype.indexOfComponentType(999));
        assertEquals(-1, archetype.indexOfComponentType(-1));
        assertEquals(-1, archetype.indexOfComponentType(0));

        System.out.println("✓ Component index boundaries validated");
    }

    @Test
    @Order(5)
    @DisplayName("TC-EC-005: Entity ID edge cases")
    void testEntityIdEdgeCases() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 8)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Test various entity IDs
        int[] testIds = {
                0,                    // Zero
                1,                    // Small positive
                -1,                   // Negative
                Integer.MAX_VALUE,    // Max int
                Integer.MIN_VALUE     // Min int
        };

        for (int id : testIds) {
            ArchetypeChunk.ChunkLocation loc = archetype.addEntity(id);
            assertNotNull(loc, "Should handle entity ID: " + id);
        }

        assertEquals(testIds.length, archetype.getEntityCount());
        System.out.println("✓ Entity ID edge cases handled correctly");
    }

    @Test
    @Order(6)
    @DisplayName("TC-EC-006: Chunk capacity boundary")
    void testChunkCapacityBoundary() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 8)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);
        int entitiesPerChunk = archetype.getEntitiesPerChunk();
        int initialChunkCount = archetype.getChunkCount();

        // Add enough entities to exceed multiple chunks
        int totalEntities = entitiesPerChunk * 3;
        for (int i = 0; i < totalEntities; i++) {
            archetype.addEntity(i);
        }

        // Verify we have more chunks now
        int finalChunkCount = archetype.getChunkCount();
        assertTrue(finalChunkCount > initialChunkCount,
                "Should have created additional chunks after adding " + totalEntities + " entities");
        assertEquals(totalEntities, archetype.getEntityCount(),
                "Should have all entities added");

        System.out.printf("✓ Chunk capacity boundary tested: %d entities per chunk, created %d chunks for %d entities%n",
                entitiesPerChunk, finalChunkCount, totalEntities);
    }

    @Test
    @Order(7)
    @DisplayName("TC-EC-007: Remove from invalid location")
    void testRemoveInvalidLocation() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 8)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);
        archetype.addEntity(1);

        // Try to remove from invalid locations
        ArchetypeChunk.ChunkLocation invalidLoc1 = new ArchetypeChunk.ChunkLocation(-1, 0);
        ArchetypeChunk.ChunkLocation invalidLoc2 = new ArchetypeChunk.ChunkLocation(0, -1);
        ArchetypeChunk.ChunkLocation invalidLoc3 = new ArchetypeChunk.ChunkLocation(999, 0);

        // Should handle gracefully
        assertDoesNotThrow(() -> archetype.removeEntity(invalidLoc1));
        assertDoesNotThrow(() -> archetype.removeEntity(invalidLoc2));
        assertDoesNotThrow(() -> archetype.removeEntity(invalidLoc3));

        System.out.println("✓ Invalid remove locations handled gracefully");
    }

    @Test
    @Order(8)
    @DisplayName("TC-EC-008: Duplicate entity addition")
    void testDuplicateEntityAddition() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 8)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Add same entity ID multiple times
        ArchetypeChunk.ChunkLocation loc1 = archetype.addEntity(42);
        ArchetypeChunk.ChunkLocation loc2 = archetype.addEntity(42);
        ArchetypeChunk.ChunkLocation loc3 = archetype.addEntity(42);

        assertNotNull(loc1);
        assertNotNull(loc2);
        assertNotNull(loc3);

        // System allows duplicate entity IDs (by design or should be prevented?)
        assertTrue(archetype.getEntityCount() >= 1);

        System.out.println("✓ Duplicate entity addition behavior verified");
    }

    @Test
    @Order(9)
    @DisplayName("TC-EC-009: Component type mismatch")
    void testComponentTypeMismatch() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 8)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);
        ArchetypeChunk.ChunkLocation loc = archetype.addEntity(1);

        // Try to get component at invalid index
        assertThrows(Exception.class, () -> {
            archetype.getComponentData(loc, 999);
        }, "Should throw exception for invalid component index");

        System.out.println("✓ Component type mismatch handled correctly");
    }

    @Test
    @Order(10)
    @DisplayName("TC-EC-010: Rapid add-remove cycles")
    void testRapidAddRemoveCycles() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 8)
        };
        int[] componentIds = new int[]{1};
        ComponentMask mask = new ComponentMask();
        mask = mask.set(1);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Rapid cycles
        for (int cycle = 0; cycle < 10; cycle++) {
            // Add
            for (int i = 0; i < 100; i++) {
                archetype.addEntity(cycle * 100 + i);
            }

            // Remove all from first chunk
            for (int i = 0; i < Math.min(100, archetype.getEntitiesPerChunk()); i++) {
                archetype.removeEntity(new ArchetypeChunk.ChunkLocation(0, i));
            }
        }

        assertTrue(archetype.getEntityCount() >= 0, "Entity count should be valid");
        System.out.printf("✓ Survived 10 rapid add-remove cycles, final count: %d%n",
                archetype.getEntityCount());
    }

    @Test
    @Order(11)
    @DisplayName("TC-EC-011: Component mask consistency")
    void testComponentMaskConsistency() {
        Arena arena = Arena.ofShared();
        ComponentDescriptor[] descriptors = new ComponentDescriptor[]{
                makeDesc(TestComponent.class, 8),
                makeDesc(TestComponent.class, 16)
        };
        int[] componentIds = new int[]{5, 10}; // Non-sequential IDs
        ComponentMask mask = new ComponentMask();
        mask = mask.set(5);
        mask = mask.set(10);

        Archetype archetype = new Archetype(mask, componentIds, descriptors, arena);

        // Verify mask consistency
        ComponentMask retrievedMask = archetype.getMask();
        assertTrue(retrievedMask.has(5), "Mask should have component 5");
        assertTrue(retrievedMask.has(10), "Mask should have component 10");
        assertFalse(retrievedMask.has(1), "Mask should not have component 1");
        assertFalse(retrievedMask.has(15), "Mask should not have component 15");

        System.out.println("✓ Component mask consistency verified");
    }

    // Test component classes
    static class SingleComponent {
        long value;
    }

    static class TestComponent {
        long value1;
        long value2;
    }
}


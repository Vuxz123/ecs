package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.api.archetype.IQuery;
import com.ethnicthv.ecs.core.api.archetype.IQueryBuilder;
import com.ethnicthv.ecs.core.archetype.ArchetypeQuery;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for parallel query execution in ArchetypeQuery
 */
public class ParallelQueryTest {

    private ArchetypeWorld world;

    @BeforeEach
    void setUp() {
        ComponentManager componentManager = new ComponentManager();
        world = new ArchetypeWorld(componentManager);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void testForEachParallelBasic() {
        // Register components and create entities
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TestComponent2.class);

        // Create 100 entities with both components
        int entityCount = 100;
        for (int i = 0; i < entityCount; i++) {
            world.createEntity(TestComponent1.class, TestComponent2.class);
        }

        // Query and count entities using parallel processing
        AtomicInteger count = new AtomicInteger(0);
        Set<Integer> processedEntities = ConcurrentHashMap.newKeySet();

        IQueryBuilder builder = world.query();
        builder.with(TestComponent1.class)
             .with(TestComponent2.class);

        IQuery query = builder.build();

        query.forEachParallel((entityId, handles, archetype) -> {
            count.incrementAndGet();
            processedEntities.add(entityId);
        });

        // Verify all entities were processed
        assertEquals(entityCount, count.get());
        assertEquals(entityCount, processedEntities.size());
    }

    @Test
    void testForEachParallelThreadSafety() {
        // Register component
        world.registerComponent(TestComponent1.class);

        // Add many entities to increase chance of parallel processing
        int entityCount = 1000;
        for (int i = 0; i < entityCount; i++) {
            world.createEntity(TestComponent1.class);
        }

        // Use thread-safe counter
        AtomicInteger counter = new AtomicInteger(0);
        Set<Integer> uniqueEntityIds = ConcurrentHashMap.newKeySet();

        IQueryBuilder builder = world.query();
        builder.with(TestComponent1.class);

        IQuery query = builder.build();
        query.forEachParallel((entityId, handles, archetype) -> {
            counter.incrementAndGet();
            uniqueEntityIds.add(entityId);

            // Simulate some work
            try {
                Thread.sleep(0, 100); // Sleep for 100 nanoseconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Verify no entities were lost or duplicated
        assertEquals(entityCount, counter.get());
        assertEquals(entityCount, uniqueEntityIds.size());
    }

    @Test
    void testForEachParallelMultipleArchetypes() {
        // Register components
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TestComponent2.class);
        world.registerComponent(TestComponent3.class);

        // Create entities with different component combinations
        // First archetype: Component1 + Component2
        for (int i = 0; i < 50; i++) {
            world.createEntity(TestComponent1.class, TestComponent2.class);
        }

        // Second archetype: Component1 + Component3
        for (int i = 0; i < 75; i++) {
            world.createEntity(TestComponent1.class, TestComponent3.class);
        }

        // Query for entities with Component1 (should match both archetypes)
        AtomicInteger count = new AtomicInteger(0);
        Set<Integer> processedEntities = ConcurrentHashMap.newKeySet();

        IQueryBuilder builder = world.query();
        builder.with(TestComponent1.class);

        IQuery query = builder.build();
        query.forEachParallel((entityId, handles, archetype) -> {
            count.incrementAndGet();
            processedEntities.add(entityId);
        });

        // Should have processed entities from both archetypes
        assertEquals(125, count.get());
        assertEquals(125, processedEntities.size());
    }

    @Test
    void testForEachParallelEmptyQuery() {
        // Register component but don't create any entities
        world.registerComponent(TestComponent1.class);

        AtomicInteger count = new AtomicInteger(0);

        IQueryBuilder builder = world.query();
        builder.with(TestComponent1.class);

        // Should not call consumer for empty query
        IQuery query = builder.build();
        query.forEachParallel((entityId, handles, archetype) -> count.incrementAndGet());

        assertEquals(0, count.get());
    }

    @Test
    void testForEachParallelNullConsumer() {
        world.registerComponent(TestComponent1.class);

        IQueryBuilder builder = world.query();
        builder.with(TestComponent1.class);

        // Should throw NullPointerException
        IQuery query = builder.build();
        assertThrows(NullPointerException.class, () -> query.forEachParallel(null));
    }

    @Test
    void testForEachParallelLargeDataset() {
        // Register component
        world.registerComponent(TestComponent1.class);

        // Add large number of entities
        int entityCount = 10000;
        for (int i = 0; i < entityCount; i++) {
            world.createEntity(TestComponent1.class);
        }

        // Process in parallel and verify count
        AtomicInteger count = new AtomicInteger(0);

        IQueryBuilder builder = world.query();
        builder.with(TestComponent1.class);

        IQuery query = builder.build();
        long startTime = System.nanoTime();
        query.forEachParallel((_, _, _) -> count.incrementAndGet());
        long duration = System.nanoTime() - startTime;

        assertEquals(entityCount, count.get());

        // Just verify it completed
        System.out.println("Processed " + entityCount + " entities in " +
                           (duration / 1_000_000.0) + " ms");
    }

    @Test
    void testCompareSequentialVsParallel() {
        // Register component
        world.registerComponent(TestComponent1.class);

        // Add entities
        int entityCount = 1000;
        for (int i = 0; i < entityCount; i++) {
            world.createEntity(TestComponent1.class);
        }

        IQueryBuilder builder = world.query();
        builder.with(TestComponent1.class);

        IQuery query = builder.build();
        // Sequential processing
        Set<Integer> sequentialEntities = new HashSet<>();
        query.forEachEntity((entityId, handles, archetype) ->
            sequentialEntities.add(entityId)
        );

        // Parallel processing
        Set<Integer> parallelEntities = ConcurrentHashMap.newKeySet();
        query.forEachParallel((entityId, handles, archetype) ->
            parallelEntities.add(entityId)
        );

        // Both should process the same entities
        assertEquals(sequentialEntities.size(), parallelEntities.size());
        assertEquals(sequentialEntities, parallelEntities);
    }

    @Test
    void testForEachParallelWithNoMatchingEntities() {
        // Register two components
        world.registerComponent(TestComponent1.class);
        world.registerComponent(TestComponent2.class);

        // Create entities with only Component1
        for (int i = 0; i < 50; i++) {
            world.createEntity(TestComponent1.class);
        }

        // Query for entities that must have Component2 (none will match)
        AtomicInteger count = new AtomicInteger(0);

        IQueryBuilder builder = world.query();
        builder.with(TestComponent2.class);

        IQuery query = builder.build();
        query.forEachParallel((_, _, _) -> count.incrementAndGet());

        assertEquals(0, count.get());
    }

    @Test
    void testForEachParallelPreservesEntityIntegrity() {
        // Register components
        world.registerComponent(TestComponent1.class);

        // Create entities
        int entityCount = 500;
        for (int i = 0; i < entityCount; i++) {
            world.createEntity(TestComponent1.class);
        }

        // Process entities and verify each is valid
        AtomicInteger validEntityCount = new AtomicInteger(0);
        AtomicInteger invalidEntityCount = new AtomicInteger(0);

        IQueryBuilder builder = world.query();
        builder.with(TestComponent1.class);

        IQuery query = builder.build();
        query.forEachParallel((entityId, handles, arch) -> {
            if (entityId > 0 && arch != null) {
                validEntityCount.incrementAndGet();
            } else {
                invalidEntityCount.incrementAndGet();
            }
        });

        assertEquals(entityCount, validEntityCount.get());
        assertEquals(0, invalidEntityCount.get());
    }
}


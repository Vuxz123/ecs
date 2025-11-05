package com.ethnicthv.ecs.archetype;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Comprehensive test suite for all Archetype-related tests.
 * This suite runs all tests for ArchetypeChunk, Archetype, parallel query functionality,
 * and the new System API with @Query annotation.
 */
@Suite
@SuiteDisplayName("ECS Test Suite")
@SelectClasses({
        ArchetypeChunkTest.class,
        ArchetypeTest.class,
        LockFreeAllocatorTest.class,
        ParallelQueryTest.class,
        ManagedComponentStoreTest.class,
        ManagedIntegrationTest.class,
        MixedManagedUnmanagedRunnerTest.class
})
public class ArchetypeTestSuite {
    // This class is used as a test suite runner
    // No implementation needed - annotations define the suite
}


package com.ethnicthv.ecs.archetype;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Comprehensive test suite for all Archetype-related tests.
 * This suite runs all tests for ArchetypeChunk, Archetype, parallel query functionality,
 * the new System API with @Query annotation, and comprehensive QA/QC tests.
 * 
 * QA/QC Test Categories:
 * - Thread Safety: Concurrent operations and race condition tests
 * - Memory Safety: Resource management and memory leak detection
 * - Edge Cases: Boundary conditions and exceptional scenarios
 * - Performance: Scalability and throughput benchmarks
 * - Data Integrity: Consistency and correctness validation
 */
@Suite
@SuiteDisplayName("ECS Comprehensive Test Suite")
@SelectClasses({
        // Core Functionality Tests
        ArchetypeChunkTest.class,
        ArchetypeTest.class,
        LockFreeAllocatorTest.class,
        ParallelQueryTest.class,
        ManagedComponentStoreTest.class,
        ManagedIntegrationTest.class,
        MixedManagedUnmanagedRunnerTest.class,
        
        // QA/QC Professional Test Suites
        ArchetypeThreadSafetyTest.class,
        ArchetypeMemorySafetyTest.class,
        ArchetypeEdgeCasesTest.class,
        ArchetypePerformanceTest.class,
        ArchetypeDataIntegrityTest.class
})
public class ArchetypeTestSuite {
    // This class is used as a test suite runner
    // No implementation needed - annotations define the suite
}


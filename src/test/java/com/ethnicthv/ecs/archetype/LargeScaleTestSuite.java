package com.ethnicthv.ecs.archetype;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Comprehensive test suite for large-scale entity operations.
 * This suite runs all large-scale tests for ArchetypeChunk and LockFreeAllocator.
 */
@Suite
@SuiteDisplayName("Large Scale ECS Test Suite")
@SelectClasses({
    ArchetypeChunkTest.class,
    LockFreeAllocatorTest.class,
    ParallelQueryTest.class
})
public class LargeScaleTestSuite {
    // This class remains empty, it is used only as a holder for the suite annotations
}

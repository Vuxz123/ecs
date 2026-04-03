# ECS Review And Fix Plan - 2026-04-03

## Scope

Review target:
- `ecs-core`
- `ecs-processor`
- `ecs-test`
- `ecs-benchmark`

Review date:
- 2026-04-03

Context:
- `./gradlew test` is currently passing.
- Existing unrelated worktree change detected in `ecs-test/src/main/java/com/ethnicthv/ecs/demo/SystemAPIDemo.java`.
- Findings below focus on correctness and API contract risks in the ECS runtime, especially around shared components, query semantics, and `EntityCommandBuffer`.

## Key Findings

### 1. Generic query path drops entities that live in shared chunk groups

Severity:
- High

Symptoms:
- Queries without `withShared(...)` can miss entities that were moved into non-default shared groups.
- Aggregate methods relying on archetype-level accessors can under-count or skip valid entities.

Root cause:
- `Archetype` default accessors read only the default group keyed by `new SharedValueKey(null, null)`.
- `ArchetypeQuery` falls back to those default-group accessors whenever no explicit shared filter is provided.

Code references:
- `ecs-core/src/main/java/com/ethnicthv/ecs/core/archetype/Archetype.java`
- `ecs-core/src/main/java/com/ethnicthv/ecs/core/archetype/ArchetypeQuery.java`

Impact:
- Hidden correctness bug in normal gameplay queries.
- Any system expecting "all entities with component X" can silently skip entities after shared assignment.

### 2. Shared filtering can silently degrade into an unfiltered query

Severity:
- High

Symptoms:
- `withShared(...)` can return broad results instead of zero results when the shared value is absent or the archetype does not expose the matching shared type.

Root cause:
- `buildQueryKey(...)` returns `null` both for "no shared filter configured" and for "configured filter cannot match".
- Call sites interpret `null` as "iterate all groups".

Code references:
- `ecs-core/src/main/java/com/ethnicthv/ecs/core/archetype/ArchetypeQuery.java`

Impact:
- False-positive query results.
- Very hard to diagnose because the query still returns data instead of failing fast or returning an empty set.

### 3. `EntityCommandBuffer` can lose previously recorded commands once a lane exceeds 64KB

Severity:
- High

Symptoms:
- Under heavier workloads, earlier commands recorded in a writer lane can disappear before playback.

Root cause:
- `ECBWriterLane.ensureCapacity(...)` swaps to a new segment and resets `offset` to zero.
- Playback only decodes the latest segment reference held by the lane.

Code references:
- `ecs-core/src/main/java/com/ethnicthv/ecs/core/archetype/EntityCommandBuffer.java`

Impact:
- Structural mutations may apply partially.
- Benchmark or production behavior can diverge from small test scenarios.

### 4. Query builder accepts unregistered component classes and fails late

Severity:
- Medium

Symptoms:
- `with(...)` records component metadata even when the component was never registered.
- Later execution can fail due to null-to-int unboxing or inconsistent query setup.

Root cause:
- `ArchetypeQuery.with(...)` appends `null` component ids into internal lists.
- Validation is deferred until iteration.

Code references:
- `ecs-core/src/main/java/com/ethnicthv/ecs/core/archetype/ArchetypeQuery.java`
- `ecs-core/src/main/java/com/ethnicthv/ecs/core/archetype/Archetype.java`

Impact:
- Configuration mistakes surface too late.
- Error messages become noisy and harder to trace back to the query declaration.

## Secondary Notes

### Query immutability contract is misleading

- `build()` is documented as returning an immutable, thread-safe query.
- Current implementation returns the same mutable builder instance.

Risk:
- Not the highest correctness bug, but the public contract is currently inaccurate.

### Managed-query support is still incomplete

- There is still a disabled placeholder around managed query access semantics in test coverage.

Risk:
- The feature surface is wider than the verified behavior.

## Fix Plan

### Phase 1. Restore correctness for query traversal

Priority:
- P0

Tasks:
- Refactor `ArchetypeQuery` to iterate across all `ChunkGroup`s when no shared filter is specified.
- Keep the optimized direct-group path only when a shared filter resolves to one concrete matching `SharedValueKey`.
- Update `count()`, `forEach()`, `forEachChunk()`, `forEachEntity()`, and `forEachParallel()` to share the same group-selection logic.

Expected result:
- Generic queries see all matching entities regardless of shared-group placement.

Regression tests:
- Query without `withShared(...)` still returns entities after `setSharedComponent(...)`.
- `count()` includes entities in non-default shared groups.
- Parallel and sequential query paths behave consistently.

### Phase 2. Make shared-filter semantics explicit and correct

Priority:
- P0

Tasks:
- Split "no shared filter configured" from "configured shared filter cannot match".
- Introduce an internal result type or sentinel that distinguishes:
  - no filter
  - one exact matching group
  - no possible match
- Ensure impossible shared filters return zero rows, zero chunks, and zero count.

Expected result:
- `withShared(...)` can never broaden a query accidentally.

Regression tests:
- Missing managed shared value returns zero matches.
- Missing unmanaged shared value returns zero matches.
- Archetypes without the requested shared type are skipped, not treated as unfiltered.

### Phase 3. Fix `EntityCommandBuffer` lane growth

Priority:
- P0

Tasks:
- Replace single-segment lane storage with chained segments or an owned segment list per lane.
- Preserve all previously written command bytes when growing capacity.
- Keep playback decoding every segment in insertion order before sorting.

Expected result:
- ECB remains correct under workloads larger than 64KB per writer lane.

Regression tests:
- Record enough commands to overflow one lane and verify all commands are applied.
- Include both single-op and multi-op mutate commands in the overflow scenario.
- Add one stress test for `setSharedManaged(...)` plus structural commands mixed together.

### Phase 4. Fail fast on invalid query builder input

Priority:
- P1

Tasks:
- Make `with(...)`, `without(...)`, and `any(...)` validate component registration immediately.
- Throw a clear `IllegalArgumentException` that names the unregistered component.
- Keep internal query state free of nullable component ids.

Expected result:
- Query misconfiguration is caught at declaration time.

Regression tests:
- Unregistered component in `with(...)` throws immediately.
- Unregistered component in `without(...)` and `any(...)` throws immediately.

### Phase 5. Tighten API contract and coverage

Priority:
- P2

Tasks:
- Decide whether `build()` should actually snapshot immutable state or whether docs should be downgraded to describe current mutable behavior.
- Add coverage for managed-query behavior or explicitly narrow the exposed contract until implemented.

Expected result:
- Public API text matches reality.
- Fewer semantic gaps between docs and runtime.

## Recommended Execution Order

1. Fix query traversal across shared groups.
2. Fix shared-filter no-match semantics.
3. Fix ECB lane growth and add overflow tests.
4. Add fail-fast query validation.
5. Clean up builder contract and remaining test/documentation gaps.

## Acceptance Gate

Before considering the refine pass complete:
- `./gradlew test` passes.
- New regression tests cover all four main findings.
- At least one test reproduces the pre-fix ECB overflow bug.
- At least one test proves generic queries include shared-group entities.
- Shared no-match queries return zero results consistently across sequential, parallel, chunk, and count paths.

# ECS Advanced Guide

Deep dives into parallel queries, performance tuning, QA/QC workflows, plus appendices that preserve the full historical docs for future reference. For a dedicated explanation of the component metadata and layout system, see `docs/COMPONENT_SYSTEM.md`. For detailed benchmark scenarios, commands, and how to read the stored results, see `docs/BENCHMARKS.md`.

## 1. Parallel Systems & Queries
This section summarizes the current query and parallel execution model and how systems should use it. Historical designs (such as older `@Query` forms and proxy-based overrides) are kept in the appendices.

The normal way to express "which entities this system cares about" is via `@Query`-annotated private methods with a `fieldInject` target. The annotation processor generates a query injector that, at system registration time, constructs and caches an `IGeneratedQuery` (often backed by an `ArchetypeQuery` under the hood) and assigns it to that field. Systems then call `generatedQuery.runQuery()` from `onUpdate`. Some generated queries also implement `IQueryBuilder` to allow lightweight per-frame filter configuration (for example, selecting a team via a shared component) while still reusing the same underlying cached builder instance.

Directly using `ArchetypeQuery` and `world.query()` is supported but considered a low-level, advanced API for specialized use cases. The demo systems in `ecs-test` (`MovementSystem`, `HealthRegenerationSystem`, `MixedUnmanagedAndManagedSystem`, `TeamFilterSystem`) all use the generated-query path.

### 1.1 Execution Model
- Systems are grouped into `SystemGroup`s (INPUT, SIMULATION, PHYSICS, RENDER, CLEANUP, and any custom groups).
- `SystemManager` runs groups in priority order (lower `priority` first), and within each group calls `onUpdate(deltaTime)` on enabled systems.
- Within `onUpdate`, systems typically invoke one or more `IGeneratedQuery.runQuery()` calls; the generated code drives the underlying archetype iteration.
- Parallelism is applied **inside** those queries via the configured `ExecutionMode` on the `@Query` annotation (for generated queries) or via low-level `ArchetypeQuery.forEachParallel(...)` if you are using the manual API.
- Systems typically hold one or more pre-built queries (via `@Query` injection or manually cached `ArchetypeQuery` instances) and reuse them on every `onUpdate` call. Query construction is relatively heavy and should not be performed repeatedly inside the hot per-frame loop.
- The `GameLoop` coordinates fixed-step updates for FIXED groups and variable updates for VARIABLE groups, but does not change query semantics.

### 1.2 Query API Tips
- Prefer `@Query`-based systems with AP-generated `IGeneratedQuery` fields as your primary way to express queries. Use `fieldInject` and `ExecutionMode` to describe which entities and how they should be processed.
- When a generated query also behaves as an `IQueryBuilder`, use it to apply lightweight per-frame filters (e.g. `withShared(TeamShared)` in `TeamFilterSystem`) while still reusing the same builder instance that was created at registration time.
- Use `ArchetypeQuery` and the `world.query()` builder directly only when you need fine-grained control (for example, experimental filters, tooling, or code that lives outside normal systems). Treat this as an advanced, low-level API.
- Use chunked processing (built into the underlying `ArchetypeQuery`) to maintain cache locality; the engine already iterates per chunk and reuses `ComponentHandle` instances.
- Use parallel execution only when:
  - You have large entity counts (≈ 1,000+), and
  - Per-entity work is CPU-bound, and
  - Your query callbacks / system logic are fully thread-safe.

### 1.3 Parallel Query Example

The low-level primitive remains `ArchetypeQuery.forEachParallel(IQuery.EntityConsumer)`, but in most user code you will simply mark a generated query with `ExecutionMode.PARALLEL` and let the generated runner dispatch work in parallel.

```java
// Build a query (the actual helper may be generated)
var query = world.query()
        .with(Position.class)
        .with(Velocity.class)
        .build();

// Thread-safe accumulator
AtomicInteger processed = new AtomicInteger(0);

// Parallel processing across chunks
query.forEachParallel((entityId, handles, archetype) -> {
    // This callback may run on worker threads – it MUST be thread-safe.
    processed.incrementAndGet();

    // Example: use handles[0], handles[1], ... to access component data
    // according to your descriptors or generated handle mapping.
});
```

Internally, `forEachParallel`:
- Filters archetypes using the configured `with` / `without` / `any` masks.
- For each matching archetype, obtains a `getChunksSnapshot()` and uses `Arrays.stream(...).parallel()` so each chunk is processed by one worker.
- Within a chunk, iterates entity slots sequentially, reusing `ComponentHandle` instances to avoid allocations.

### 1.4 Thread-Safety Rules
When using `forEachParallel`:
- Treat the `EntityConsumer` body as multi-threaded code:
  - Use `AtomicInteger`, `AtomicLong`, or `LongAdder` for counters.
  - Use `ConcurrentHashMap` / `ConcurrentLinkedQueue` for shared collections.
  - Prefer per-thread batching (e.g. thread-local lists) over coarse-grained `synchronized` blocks.
- Do **not** rely on processing order; entities may be visited in any order.
- Avoid blocking I/O or long critical sections in the consumer, as they can negate parallel speedups.

Historical examples of the parallel API (including chunk-level diagrams and troubleshooting) are preserved in `PARALLEL_QUERY_GUIDE.md` and `README_PARALLEL_SYSTEM.md`.

### 1.5 Query Best Practices

This section summarizes practical rules for using the query APIs efficiently and safely.

**Choose the right API**
- Use `@Query`-based systems with `IGeneratedQuery` fields as the default. They are easier to read, safer to evolve, and let the annotation processor optimize query construction and caching.
- Reach for `ArchetypeQuery` and `world.query()` only when you need advanced control (for example, custom filters, experimental iteration patterns, or integration code that lives outside a normal `ISystem`).

**Cache and reuse queries**
- Building a query (either via generated code from `@Query` or via the `ArchetypeQuery` builder) computes matching archetype sets and supporting data structures; this is relatively heavy.
- Generated injectors construct and cache `IGeneratedQuery` / `IQueryBuilder` instances once per system at registration time; do not rebuild them inside `onUpdate`.
- When using a builder-style query (like in `TeamFilterSystem`), treat per-frame operations (e.g. `withShared(...).build().runQuery()`) as light filter configuration on a cached builder, not as reconstructing the entire query.

**Keep queries stable; move conditions into components**
- Prefer stable query shapes (required/optional/excluded component sets) that rarely change at runtime.
- Represent dynamic conditions with component values, tags, or flags instead of reconfiguring queries repeatedly.
- If you must change the component mask, do it infrequently (for example, when entering or leaving a major game state) and reuse the updated query afterward.

**Design for hot loops**
- Assume the per-entity callback is in a hot loop: keep it small, avoid allocations, and avoid heavy logging.
- When using parallel execution (either via `ExecutionMode.PARALLEL` or direct `forEachParallel`), treat the callback as multi-threaded code and follow the thread-safety rules from Section 1.4.
- When in doubt, start with sequential queries using cached generated queries, then upgrade to parallel execution if profiling shows a consistent bottleneck and enough work per entity.

## 2. Performance & Memory Tuning
Insights summarized from `IMPROVEMENTS.md` and `COMPONENT_SYSTEM.md`.

- True SoA layout per component dimension → cache-friendly access & SIMD-friendly loops.
- `ComponentManager` centralizes registration; deactivate auto-registration when orchestrating load order.
- Utilize bulk operations (`count()`, `forEachChunk(...)`) for query preflight checks.
- Keep per-system scratch buffers off-heap to avoid GC interference, especially in hot loops.

**Structural changes and batching.** Creating/destroying entities or adding/removing components reconfigures archetypes and can be significantly more expensive than plain component reads/writes. In particular, doing these changes directly inside hot query callbacks (especially with `ExecutionMode.PARALLEL`) can:
- Disturb chunk iteration while it is in progress.
- Introduce contention on internal structures.
- Make reasoning about correctness and performance harder.

Recommendation:
- Use an `EntityCommandBuffer` (or equivalent command queue) to record structural changes while queries are running.
- Apply the buffered commands at a well-defined sync point (e.g., immediately after `runQuery()` or at the end of the frame) on a single thread.
- Combine this with query caching: build queries once, read via cached queries every frame, and route structural writes through command buffers rather than direct `world.add/remove/destroy` calls from inside query callbacks.

**Benchmarks.** The `ecs-benchmark` module and the result files under `results/` (for example `results-11-52-14-11-2025.txt`) provide empirical measurements of this library’s behavior under load: entity creation and destruction, component registration and migration, sequential and parallel query iteration, structural changes (per-entity vs batched), and `EntityCommandBuffer` usage. These numbers are intended to characterize how this implementation behaves in different scenarios and to guide tuning of systems and data layouts. They are not presented as a comparison against any other engine or framework. For a more detailed walkthrough and run instructions, see `docs/BENCHMARKS.md`.

## 3. QA / QC Process
Key practices distilled from `QA_QC_CHECKLIST.md`, `QA_QC_TEST_CASES.md`, and `QA_QC_TEST_SUITE.md`.

- **Checklist:** verify annotation processing, component registration, system scheduling order, and foreign-memory flags before any release.
- **Test cases:** cover entity creation/destruction, component add/remove churn, parallel queries under load, and `GameLoop` pause/resume sequences.
- **Test suite:** `ecs-test` is structured to mirror real-world scenarios; extend it rather than crafting ad hoc demos.

Refer to Appendix C/D/E for the full checklists and suite definitions.

## 4. Publishing & Release Workflow
- Root `build.gradle.kts` applies Vanniktech Maven Publish with `SonatypeHost.CENTRAL_PORTAL`.
- `secring.gpg` path is normalized (e.g. `rootProject.file("secring.gpg").absolutePath.replace("\\", "/")`) to avoid Windows URI issues.
- Tag releases per milestone completion (see `docs/ROADMAP.md`), then run:
  - `gradlew publishAllPublicationsToMavenCentralRepository` to send artifacts to Maven Central.
  - `gradlew publishToMavenLocal` when you need local testing.

## 5. Appendices (Full Legacy Docs)
- **Appendix A:** `README_PARALLEL_SYSTEM.md`
- **Appendix B:** `PARALLEL_QUERY_GUIDE.md`
- **Appendix C:** `QA_QC_CHECKLIST.md`
- **Appendix D:** `QA_QC_TEST_CASES.md`
- **Appendix E:** `QA_QC_TEST_SUITE.md`
- **Appendix F:** `COMPONENT_SYSTEM.md`
- **Appendix G:** `IMPROVEMENTS.md`

> Keep these files in repo for historical fidelity; future updates should edit the source docs then summarize changes here.

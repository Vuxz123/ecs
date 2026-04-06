# Query Scheduler Optimization Plan

This document captures the current optimization direction for the boid demo and the ECS generated-query runtime after profiling large-world parallel steering workloads.

The goal is to reduce scheduler overhead, hot-path contention, and avoidable render-side copy cost before attempting lower-level CPU affinity work.

## 1. Current Profiler Findings

The latest IntelliJ profiler runs point to three dominant costs:

1. `SteeringSystem.computeSteering(...)`
   This is still the real simulation hot loop, especially on worker threads.

2. Render-side position extraction
   `BoidSimulation.copyPositions(...)` currently walks entities via `world.getComponent(...)` and `ComponentManager.acquireBoundHandle(...)` every frame.

3. Generated parallel query scheduling overhead
   Parallel query execution still spends measurable time in generated scheduler plumbing and stream/fork-join dispatch.

Secondary findings:
- `shortestDelta` and `flattenWrappedCell` are no longer the dominant bottlenecks.
- `sqrt`-level micro-optimizations are not the main limiter anymore.
- `ConcurrentLinkedDeque` in `ComponentManager` is showing up in hot paths and should be treated as a contention source.

## 2. Recent Improvements Already Applied

The following changes are already in place:
- Precomputed wrapped-neighbor lookup for spatial cells.
- Compact per-cell snapshot arrays for neighbor traversal.
- Raw `ComponentHandle` use in `SteeringSystem` instead of typed-handle rebinding in the hot query.
- Generated `PARALLEL` query path moved away from `List<Runnable> + parallelStream`.
- Density-normalized headless benchmark mode for fairer large-world comparisons.

These changes improved the benchmark materially, but they also clarified that the next bottlenecks are scheduler design, handle reuse strategy, and render snapshot publication.

## 3. Decision: Dedicated Workers Before Core Affinity

Proposed direction:
- Build a fixed ECS worker pool first.
- Move generated parallel query dispatch onto that pool.
- Keep thread affinity as an optional experimental step later.

Reason:
- The profiler does not currently show a primary problem of OS thread migration.
- It does show scheduler overhead, queueing overhead, and hot-path contention.
- Pinning threads to physical cores too early would add complexity without addressing the clearest current costs.

## 4. Optimization Objectives

Primary objectives:
- Reduce generated-query scheduling overhead.
- Remove concurrent-handle-pool contention from query hot paths.
- Eliminate per-frame render extraction through `world.getComponent(...)`.
- Keep benchmark methodology honest and reproducible.

Non-objectives for the next phase:
- No early NUMA tuning.
- No mandatory thread affinity.
- No platform-specific worker pinning in the default path.

## 5. Phase Plan

### Phase A. Benchmark Baseline Lock

Goal:
- Freeze a reproducible baseline before changing runtime scheduling.

Tasks:
- Use headless benchmark with:
  - `100k`
  - `parallel`
  - `density-normalized`
  - fixed warm-up/sample settings
- Save CSV outputs for before/after comparisons.
- Record machine details when publishing benchmark screenshots.

Deliverable:
- Baseline benchmark record for scheduler work.

Acceptance:
- Same command can be rerun locally and produce comparable numbers.

### Phase B. Dedicated ECS Worker Pool

Goal:
- Replace ad-hoc stream scheduling with a fixed pool intended for ECS query execution.

Tasks:
- Introduce `EcsWorkerPool`.
- Default worker count to physical core count or a safe override strategy.
- Keep per-worker local buffers and reusable state.

Deliverable:
- Reusable worker pool owned by ECS runtime or query runtime.

Acceptance:
- Parallel query execution can dispatch onto the dedicated pool without using `parallelStream`.

### Phase C. Generated Query Dispatch Rewrite

Goal:
- Move generated `PARALLEL` query execution onto the dedicated worker pool.

Tasks:
- Update `QueryProcessor` codegen.
- Replace `IntStream.range(...).parallel()` dispatch.
- Split chunk work into explicit ranges or batches.

Deliverable:
- Generated query runners using direct pool scheduling.

Acceptance:
- Generated parallel runners no longer depend on stream-based scheduling.

### Phase D. Chunk Batching

Goal:
- Avoid overly fine-grained chunk work submission.

Tasks:
- Group multiple chunks into one work item.
- Make batch sizing configurable or heuristically derived.
- Keep work distribution deterministic enough for profiling.

Deliverable:
- Batched chunk scheduling in generated parallel queries.

Acceptance:
- Profiler shows reduced scheduling overhead compared to pre-batching runs.

### Phase E. Per-Worker Handle Reuse

Goal:
- Remove `ConcurrentLinkedDeque` contention from hot query execution.

Tasks:
- Replace shared handle-pool traffic with per-worker or thread-local handle reuse.
- Keep compatibility with current `ComponentManager` APIs where possible.
- Audit generated query runners to ensure no cross-thread handle reuse occurs.

Deliverable:
- Query hot path no longer depends on a global concurrent handle pool.

Acceptance:
- `ComponentManager.acquireHandle()` and pool operations no longer appear prominently in profiler traces for query execution.

### Phase F. Render Snapshot Publication

Goal:
- Stop paying main-thread per-boid extraction cost through ECS world lookups.

Tasks:
- Publish a contiguous render snapshot after movement or spatial rebuild.
- Let `BoidRenderer` upload directly from that snapshot.
- Preserve render stride and reduced-render mode.

Deliverable:
- Renderer consumes a contiguous float buffer or equivalent snapshot.

Acceptance:
- `BoidSimulation.copyPositions(...)` is no longer dominated by `world.getComponent(...)` and bound-handle acquisition.

### Phase G. Post-Optimization Re-Profile

Goal:
- Re-measure after scheduler, handle, and render-copy work lands.

Tasks:
- Re-run baseline benchmark.
- Capture new profiler traces for:
  - main thread
  - worker threads
  - query scheduling
- Compare fixed-bounds and density-normalized results.

Deliverable:
- Updated benchmark and profiler comparison set.

Acceptance:
- Results show whether the next limiter is still scheduler-bound, memory-bound, or render-bound.

### Phase H. Experimental Thread Affinity

Goal:
- Evaluate CPU affinity only after the pool and batching model are stable.

Tasks:
- Add optional thread-affinity backend behind a flag.
- Start with Windows support first.
- Allow `off` by default and `physical` as experimental mode.

Deliverable:
- Optional affinity mode for advanced benchmarking only.

Acceptance:
- Affinity is measurable as a controlled experiment, not required for normal execution.

## 6. Proposed Runtime Flags

Planned flags for benchmark and runtime experimentation:
- `--worker-count=<n>`
- `--scheduler=stream|pool`
- `--affinity=off|physical`
- `--density-normalized`
- `--fixed-bounds`
- `--fast-math`
- `--precise-math`

These flags should make benchmark methodology explicit in both CLI output and exported CSV rows.

## 7. Benchmark Methodology Notes

To keep benchmark numbers meaningful:
- Prefer density-normalized runs for cross-entity-count comparisons.
- Use fixed-bounds runs only when intentionally testing overcrowded-neighborhood stress.
- Treat headless and interactive numbers as separate categories.
- Report scheduler mode and worker count alongside FPS and frame time.

Suggested default benchmark command:

```powershell
./gradlew :boid-demo:run --args="--headless-benchmark --boids 100000 --warmup 240 --samples 600 --mode parallel --density-normalized"
```

## 8. Exit Criteria For This Optimization Track

This optimization track is considered successful when:
- Parallel generated queries no longer rely on stream scheduling.
- Global concurrent handle-pool contention is removed from hot execution paths.
- Render snapshot upload no longer requires per-boid ECS world lookups.
- Benchmark output includes scheduler and methodology context.
- Thread affinity, if added, remains optional and clearly labeled experimental.

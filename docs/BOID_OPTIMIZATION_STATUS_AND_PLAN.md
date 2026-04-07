# Boid Optimization Status And Plan

This document turns the current boid-performance discussion into an implementation-focused status check and next-step plan. It is intentionally grounded in the code that exists today, not just in desired architecture.

## 1. Executive Summary

The boid demo has already crossed the important architectural threshold:
- ECS iteration is no longer the primary bottleneck.
- Steering no longer performs open-ended spatial scans in the hot math loop.
- Fixed-size neighbor buffers now live inside ECS chunk storage.
- The simulation already uses a split pipeline:

```text
SpatialHashSystem
-> BuildNeighborSystem
-> SteeringSystem
-> MovementSystem
```

The remaining performance problem at `100k+` boids is mostly:
- bounded but still heavy `O(N * K)` steering compute
- memory bandwidth / cache pressure from neighbor-driven reads
- parallel workload imbalance from fixed chunk/range batching

That means the optimization direction is now:
- control workload harder
- improve load balance
- reduce wasted math and memory traffic

Not:
- redesign ECS storage again
- move spatial cells into shared components

## 2. Current Implementation Status

### Already Implemented

#### ECS / Runtime

- Dedicated ECS worker pool for generated parallel queries.
- Fixed range batching in the generated query scheduler.
- Dedicated simulation thread mode plus mailbox/snapshot handoff.
- `EntityCommandBuffer` usage for boid reset destruction path.

#### Boid Data / Pipeline

- `SpatialHashGrid` for frame-local spatial indexing.
- `BuildNeighborSystem` separated from `SteeringSystem`.
- Fixed neighbor buffer moved into ECS storage via `NeighborBuffer` unmanaged component.
- Raw `ComponentHandle` hot-path access in both `BuildNeighborSystem` and `SteeringSystem`.
- Boid-specific adaptive batching over `boidIndex` ranges for `BuildNeighborSystem` and parallel `SteeringSystem`.
- Configurable `maxNeighbors`.
- Configurable neighbor refresh interval.
- Density-normalized benchmark mode.

#### Neighbor-Build Optimizations

- Cell size chosen close to neighbor radius.
- Precomputed neighbor-cell lookup and wrap offsets.
- Randomized neighbor-cell visit order.
- Sampling within dense cells via `targetSamplesPerCell`.
- Coarse cell-center reject before per-candidate distance checks.
- Cheap exact per-candidate axis reject before `distanceSq`.

#### Steering Optimizations

- Steering loop iterates fixed neighbor buffers only.
- Fast inverse sqrt path exists behind `fastMath`.
- Max-force clamp precomputes squared thresholds.

### Partially Implemented

- Temporal decimation exists indirectly through neighbor refresh interval, but not yet as explicit per-boid update-rate control.

### Not Implemented Yet

- Cost-based adaptive batching using density or neighbor cost as the scheduler signal.
- Steering-specific work stealing beyond the current worker-pool range drain.
- SIMD / Java Vector API path.
- Quality tiers that deliberately disable or simplify alignment/cohesion for high-count modes.
- Interpolated rendering between fixed sim snapshots for low-TPS dedicated-thread runs.

## 3. Evaluation Of The Optimization Summary

### What The Summary Gets Right

- The real scaling problem is now compute plus memory bandwidth, not ECS basics.
- Spatial hash reduces search space but does not guarantee stable workload.
- Bounded `K` is mandatory for predictable large-scale performance.
- Separating neighbor build from steering is the correct production architecture.
- Parallel performance now depends more on batching and balance than on raw query throughput.

### What Needs Correction Or Clarification

#### “Fixed Neighbor Buffer” is not a future idea anymore

This is already implemented. The current engine now supports inline fixed primitive arrays, and boid neighbor data is stored in ECS chunk data instead of an external neighbor array cache.

#### “Spatial hash only as data, BuildNeighbor fills buffer, Steering is pure compute”

This is also already the current design.

#### “SoA layout is enough”

Not true by itself. The current code already proves that layout alone is not enough; the bigger wins came from:
- bounding neighbor count
- reducing candidate count
- moving to fixed neighbor buffers
- eliminating unnecessary handle/scheduler overhead

#### “Adaptive batching” is still aspirational

The repo currently has worker-pool batching, but it is based on item counts, not estimated steering cost. This is the biggest gap between the current implementation and the idealized summary.

## 4. Recommended Target Architecture

The target architecture should stay:

```text
SpatialHashSystem
  - capture positions/velocities
  - order by cell

BuildNeighborSystem
  - fill fixed-size NeighborBuffer in ECS chunk storage
  - apply cheap culling and sampling

SteeringSystem
  - read NeighborBuffer only
  - pure math / force accumulation

MovementSystem
  - integrate + wrap bounds
  - publish render snapshot
```

This architecture is already in place. Future work should refine it rather than replace it.

## 5. Practical Assessment Of Remaining Bottlenecks

### Bottleneck A: Steering Math And Memory Traffic

Even with fixed `K`, steering still reads:
- self position
- self velocity
- neighbor positions
- neighbor velocities

For `100k` boids and `K=16..24`, that is still a large volume of dependent memory traffic and vector math.

### Bottleneck B: BuildNeighbor Cost In Dense Regions

The code now avoids full sequential scans, but dense regions still cost more because:
- more sampled candidates survive
- more neighbor slots fill quickly
- different boids have different effective workloads

### Bottleneck C: Parallel Load Imbalance

The current scheduler balances by chunk count / item count, not by estimated work. That means:
- sparse chunks can finish quickly
- dense chunks can become stragglers
- worker utilization drops at the tail

## 6. Implementation Plan For The Remaining Work

## Phase A: Benchmark Baseline And Config Sweep

Goal:
- Make the next optimizations measurable and comparable.

Tasks:
- Standardize benchmark runs for:
  - `25k`, `50k`, `100k`, `200k`
  - `SEQUENTIAL` and `PARALLEL`
  - `fastMath on/off`
  - `maxNeighbors = 12/16/24`
  - `targetSamplesPerCell = 8/12/16`
- Store benchmark tables in docs or CSV snapshots.
- Treat density-normalized mode as the default benchmark mode.

Deliverable:
- A repeatable benchmark matrix for boid counts and quality settings.

Notes:
- This is required before large scheduler changes.

## Phase B: Cheap Per-Candidate Reject In Neighbor Build

Status:
- Implemented

Goal:
- Reduce wasted math before `distanceSq`.

Implemented:
- `SpatialHashGrid.buildFixedNeighborsForBoid(...)` now rejects candidates by axis bounds before `distanceSq`.
- `SteeringSystem` applies the same cheap axis reject when re-validating stale neighbors from refresh intervals.
- Existing cell-center reject stays in place.

Rationale:
- The current code jumps directly to `distanceSq`.
- Cheap axis-aligned rejection can cut multiplies and dependent ops in dense samples.

Deliverable:
- Neighbor-build path with exact candidate early reject before squared-distance math.

## Phase C: Adaptive Batching For Parallel Work

Goal:
- Reduce stragglers in dense scenes.

Status:
- Implemented for boid hot paths

Implemented:
- `BuildNeighborSystem` no longer uses generated parallel query scheduling; it batches by `boidIndex` using a density proxy (`cell population`).
- Parallel `SteeringSystem` no longer uses generated chunk-parallel query scheduling; it batches by `boidIndex` using previous neighbor counts as a cost proxy.
- Both systems bind raw `ComponentHandle` instances directly to cached ECS component segments per boid.
- Adaptive ranges are planned greedily toward a target cost, then drained through `EcsWorkerPool`.

Recommended first scope:
- Implement adaptive batching for `BuildNeighborSystem` and/or `SteeringSystem` only.
- Do not require a full generic ECS scheduler redesign as the first step.

Rationale:
- The generic worker pool is already good enough for many systems.
- Boid steering has unusual cost skew and deserves a specialized strategy first.

Observed benchmark:
- `100k`, `PARALLEL`, `density-normalized`, `precise-math`
- before this phase: about `24.54 FPS`, `40.752 ms/frame`
- after boid-specific adaptive batching: about `26.65 FPS`, `37.528 ms/frame`

## Phase D: Temporal Decimation And Quality Tiers

Goal:
- Trade visual precision for stable large-scale throughput in stress modes.

Tasks:
- Add explicit update-rate modes:
  - update all boids every tick
  - update half the boids each tick
  - update thirds or quarters for extreme stress
- Keep fixed neighbor buffers and interpolation-friendly output.
- Add named quality presets:
  - `Quality`
  - `Balanced`
  - `Stress`

Optional behavior simplifications:
- reduce or disable cohesion in stress mode
- reduce alignment weight in stress mode

Rationale:
- Neighbor refresh interval helps, but it is not the same as systematic update decimation.
- A production demo should make these tradeoffs explicit and benchmarkable.

Deliverable:
- Stable high-count modes with predictable quality/performance tradeoffs.

## Phase E: SIMD Experiment

Goal:
- Test whether Java Vector API provides a real gain on the current steering math path.

Tasks:
- Prototype a vectorized path for:
  - distance squared
  - separation accumulation
  - alignment/cohesion accumulation
- Keep it behind a feature flag.
- Compare:
  - scalar precise math
  - scalar fast math
  - vector path

Notes:
- This is only worth doing after workload control and batching are in a good place.
- SIMD should be treated as an experiment until benchmarks show a consistent win.

Deliverable:
- Data-backed decision on whether a Vector API path should stay.

## Phase F: Render Interpolation For Dedicated Thread Mode

Goal:
- Remove visible stutter when ECS TPS is lower than render FPS.

Tasks:
- Publish previous and current sim snapshots plus timing metadata.
- Render interpolated positions on the main thread.
- Keep benchmark mode able to disable interpolation for pure throughput measurement.

Rationale:
- This is not a simulation-throughput optimization.
- It is necessary for presentation quality when the sim thread runs at fixed TPS and the renderer is uncapped.

Deliverable:
- Smooth visual output in dedicated-thread mode without changing ECS TPS.

## 7. Recommended Production Defaults

Use these as the initial “balanced” target config:

```text
maxNeighbors = 16
targetSamplesPerCell = 12..16
neighborRefreshIntervalTicks = 2
fastMath = ON for stress mode
executionMode = PARALLEL
densityNormalized = ON for comparisons
```

For stress presets:

```text
maxNeighbors = 12
reduced render stride enabled
optional temporal decimation enabled
```

## 8. What Not To Do

- Do not move dynamic `SpatialCell` values into shared components.
- Do not collapse neighbor build back into `SteeringSystem`.
- Do not treat SIMD as the first or only optimization lever.
- Do not assume fixed range batching is “good enough” without profiling dense scenes.

## 9. Short Version

The boid demo is already on the right architecture. The major completed wins are:
- fixed neighbor buffers
- ECS-owned inline neighbor storage
- dedicated build-neighbor pass
- raw handle hot path
- dedicated simulation thread

The next real gains are most likely to come from:
- cheap exact reject before `distanceSq`
- adaptive batching based on density/cost
- explicit quality/update-rate scaling
- optional SIMD after the above is stable

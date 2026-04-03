# 3D Boid Flocking Demo Plan

This document defines a presentation-ready boid demo for the ECS repository and breaks implementation into concrete phases. The goal is to showcase the current strengths of the ECS runtime without conflating simulation cost with avoidable structural churn.

## 1. Objectives

Primary goals:
- Deliver a visually clear 3D boid flocking demo that is suitable for demos, screenshots, and live profiling.
- Showcase archetype iteration, chunk-oriented memory layout, fixed-step simulation, and parallel query execution.
- Expose debug information that helps explain ECS behavior to an audience: FPS, entity count, archetype count, chunk count, and chunk occupancy.

Secondary goals:
- Provide a stress mode for higher boid counts.
- Keep the code isolated from `ecs-test` so rendering dependencies and native setup do not leak into test/demo modules.

## 2. Why Boids Fit This ECS

Boids are a strong fit for this repository because the simulation naturally emphasizes:
- Repeated full-world component iteration over `Position`, `Velocity`, and `Acceleration`.
- Chunk-parallel work for movement and steering passes.
- Stable fixed-step updates through `GameLoop`.
- Observable performance scaling as entity count rises.

What boids do **not** prove by themselves:
- Native ECS support for nearest-neighbor search.
- Efficient per-frame shared-component migration.

The neighbor search still requires a spatial index. That index should be implemented deliberately so the demo measures simulation and query throughput rather than repeated structural moves.

## 3. Recommended Scope

Recommended implementation target:
- Interactive 3D demo at `100k+` boids on a strong desktop.
- Stress mode for `250k-500k` visible boids depending on hardware.
- Optional benchmark mode that can push toward `1M` boids with simplified or reduced rendering.

Do not hard-claim `1M @ 60 FPS` for the full visual mode. That target depends more on rendering and spatial-neighbor strategy than on the ECS query core alone.

## 4. Proposed Module Layout

Create a dedicated module:

```text
boid-demo/
  build.gradle.kts
  src/main/java/com/ethnicthv/ecs/boid/
    DemoMain.java
    sim/
      BoidSimulation.java
      SimulationConfig.java
      SimulationStats.java
    sim/components/
      Position3.java
      Velocity3.java
      Acceleration3.java
      CellKey.java
      BoidColor.java
      FlockShared.java
    sim/systems/
      SpatialHashSystem.java
      SteeringSystem.java
      MovementSystem.java
      ResetAccelerationSystem.java
    render/
      BoidRenderer.java
      CameraController.java
      DebugOverlay.java
    debug/
      WorldStatsCollector.java
      ChunkOccupancySnapshot.java
```

Rationale:
- `boid-demo` keeps LWJGL and ImGui out of `ecs-test`.
- The simulation package can depend on `ecs-core` and the annotation processor cleanly.
- Rendering, UI, and debug tooling stay separate from the ECS systems.

## 5. Data Model

Recommended components:

| Component | Kind | Purpose |
| --- | --- | --- |
| `Position3` | unmanaged instance | Current world position |
| `Velocity3` | unmanaged instance | Current linear velocity |
| `Acceleration3` | unmanaged instance | Accumulated steering force for this tick |
| `CellKey` | unmanaged instance | Cached spatial cell id for the current frame |
| `BoidColor` | unmanaged instance | Render tint or flock palette index |
| `FlockShared` | shared managed or stable tag | Long-lived grouping for multiple flocks |

Notes:
- `CellKey` should be a normal instance component, not a shared component.
- `FlockShared` is a valid shared component because flock identity is stable and low-cardinality.
- A separate `Renderable` component is unnecessary if every boid uses the same render path.

## 6. System Design

Recommended systems:

| System | Update Mode | Query Shape | Responsibility |
| --- | --- | --- | --- |
| `ResetAccelerationSystem` | FIXED | `Acceleration3` | Clear force accumulation before steering |
| `SpatialHashSystem` | FIXED | `Position3`, `CellKey` | Rebuild or refresh the spatial grid |
| `SteeringSystem` | FIXED | `Position3`, `Velocity3`, `Acceleration3`, `CellKey` | Compute separation, alignment, and cohesion in one pass |
| `MovementSystem` | FIXED | `Position3`, `Velocity3`, `Acceleration3` | Euler integration, speed clamp, world bounds |
| `RenderSystem` | VARIABLE | read-only snapshot | Upload positions and draw boids |
| `DebugOverlay` | VARIABLE | none | Show FPS, world stats, toggles, and timings |

Important design choice:
- Merge separation, alignment, and cohesion into a single steering pass. This avoids three neighborhood scans per boid and better reflects hot-loop performance.

## 7. Spatial Index Strategy

The spatial structure should live outside shared-component grouping.

Recommended approach:
- Keep `CellKey` as a normal component updated each fixed tick.
- Build a frame-local spatial hash structure in `SpatialHashSystem` using preallocated primitive arrays or buffers.
- Let `SteeringSystem` read that structure to visit nearby cells only.

Avoid this approach:
- Updating `setSharedComponent(entity, SpatialCell...)` every frame.

Reason:
- In this ECS, changing a shared value moves the entity between `ChunkGroup`s.
- That is a structural operation and will dominate timing once the flock is large.
- The demo would then benchmark migration churn more than query iteration.

## 8. Rendering And UI

Recommended stack:
- LWJGL 3 for windowing and OpenGL.
- Dear ImGui for debug overlays and controls.

Rendering direction:
- Use instanced rendering or point sprites rather than per-entity meshes.
- Keep a single GPU upload path for positions and optional per-boid color.
- Make chunk occupancy and archetype stats visible in a compact overlay, not a separate tool.

Suggested debug controls:
- boid count preset
- sequential vs parallel steering
- fixed tick rate
- neighbor radius
- separation/alignment/cohesion weights
- stress mode
- chunk occupancy visualization

## 9. Performance Story To Present

This demo should tell a clear ECS story:
- Fixed-step simulation keeps motion stable.
- Parallel queries reduce steering cost at large entity counts.
- Chunk-oriented layout keeps hot loops predictable.
- Debug overlays connect simulation behavior to internal world layout.

Metrics to display:
- frame FPS
- simulation ms
- render ms
- boid count
- archetype count
- chunk count
- average chunk occupancy
- top occupancy buckets
- sequential vs parallel mode

## 10. Risks And Constraints

Technical risks:
- Rendering will become the bottleneck before ECS iteration at high counts.
- Naive neighbor search will invalidate any ECS throughput claims.
- Per-frame allocations in spatial indexing or rendering will introduce GC noise.
- Continuous shared-component migration will distort benchmark results.

Mitigations:
- Preallocate spatial buffers.
- Use a single steering pass.
- Keep rendering simple and batched.
- Treat stress mode and presentation mode as separate presets.

## 11. Phase Plan

### Phase 0. Module Bootstrap

Goal:
- Create the `boid-demo` Gradle module and verify it compiles with `ecs-core` plus the processor.

Tasks:
- Add `boid-demo` to `settings.gradle.kts`.
- Create `boid-demo/build.gradle.kts`.
- Wire Java toolchain, preview flags, and application entrypoint.
- Add LWJGL and ImGui dependencies.

Deliverable:
- Empty window or basic app startup from `DemoMain`.

Acceptance:
- `./gradlew :boid-demo:build` succeeds.
- `./gradlew :boid-demo:run` opens a window.

### Phase 1. Headless ECS Simulation Core

Goal:
- Stand up the boid world and run fixed-step updates without rendering.

Tasks:
- Define simulation components.
- Build `BoidSimulation` around `ECS`.
- Implement spawn/reset logic for boid batches.
- Add `ResetAccelerationSystem` and `MovementSystem`.

Deliverable:
- Headless simulation that updates boids deterministically.

Acceptance:
- Can spawn and step at least `100k` boids.
- No per-frame structural changes outside explicit reset/spawn paths.

### Phase 2. Spatial Hash And Steering

Goal:
- Implement correct flocking behavior with a scalable neighborhood search.

Tasks:
- Add frame-local spatial hash buffers.
- Implement `SpatialHashSystem`.
- Implement unified `SteeringSystem`.
- Add world bounds and speed/force clamps.

Deliverable:
- Boids exhibit stable flocking motion in headless mode.

Acceptance:
- Steering works in sequential mode first.
- Simulation remains stable at fixed timestep under sustained load.

### Phase 3. Parallel Simulation Path

Goal:
- Expose and validate chunk-parallel steering execution.

Tasks:
- Add `ExecutionMode.PARALLEL` or equivalent path for steering.
- Ensure thread-safe access to the spatial index.
- Capture timings for sequential and parallel simulation modes.

Deliverable:
- Toggleable sequential/parallel steering mode.

Acceptance:
- Same visible behavior across both modes within tolerance.
- Parallel mode shows clear improvement at large counts on multi-core hardware.

### Phase 4. Rendering Pipeline

Goal:
- Render the live simulation in 3D with minimal overhead.

Tasks:
- Implement camera controls.
- Upload position/color buffers each frame.
- Add point-sprite or instanced draw path.
- Add world-space bounds and visual orientation cues.

Deliverable:
- Interactive 3D boid visualization.

Acceptance:
- Can view and navigate a `100k+` flock.
- Render path does not require per-entity draw calls.

### Phase 5. Debug Overlay And ECS Introspection

Goal:
- Make the demo explain itself.

Tasks:
- Add ImGui overlay.
- Collect world stats from archetypes and chunks.
- Visualize chunk occupancy and core timing metrics.
- Add runtime controls for flock parameters and count presets.

Deliverable:
- Presentation-ready debug panel.

Acceptance:
- Overlay shows FPS, boid count, archetype count, chunk count, occupancy, and mode toggles.
- Presenter can switch between readability mode and stress mode live.

### Phase 6. Stress Mode And Benchmark Packaging

Goal:
- Turn the demo into a reproducible benchmark showcase.

Tasks:
- Add high-count presets and reduced-render mode.
- Add warm-up flow before measuring.
- Add optional CSV or log export for timings.
- Document recommended demo presets per hardware tier.

Deliverable:
- Stress mode suitable for recordings, profiling, and benchmark screenshots.

Acceptance:
- Can run large-count presets reliably.
- Benchmark numbers are labeled clearly as hardware-dependent.

## 12. Recommended Build Order

Implement in this order:
1. Phase 0
2. Phase 1
3. Phase 2
4. Phase 4
5. Phase 5
6. Phase 3
7. Phase 6

Reason:
- The rendering path can be added once the sequential simulation is already correct.
- Parallel steering should be added only after the spatial index and headless behavior are validated.
- This keeps correctness debugging simpler and prevents concurrency from masking simulation bugs.

## 13. Definition Of Done

The boid demo is considered done when:
- A dedicated `boid-demo` module builds and runs independently.
- The simulation uses fixed-step ECS systems.
- Steering supports both sequential and parallel execution.
- The UI exposes performance and ECS layout statistics.
- The demo includes at least one presentation preset and one stress preset.
- The docs explain the benchmark story and its limitations honestly.

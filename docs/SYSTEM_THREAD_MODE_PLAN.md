# System Thread Mode Plan

This document proposes the next runtime option for ECS execution:

- run system updates directly on the caller thread (`MAIN_THREAD`)
- run system updates on a dedicated simulation thread (`DEDICATED_THREAD`)

The goal is to make this an explicit runtime choice without breaking the current single-threaded control flow or the existing parallel-query worker pool.

## 1. Why Add This Option

Current state:

- `SystemManager.update(...)` and `updateGroup(...)` are synchronous.
- `GameLoop.run()` owns the outer loop and executes on the caller thread.
- `DemoMain` already bypasses `GameLoop` and drives fixed-step simulation manually on the main thread.

What we want:

- preserve the current low-overhead main-thread mode
- add an opt-in dedicated system thread for applications that want render/UI on one thread and ECS stepping on another

This is different from the query worker pool:

- the worker pool parallelizes work inside a system/query
- the new mode controls which thread owns the outer `SystemManager.update*()` calls

## 2. Non-Negotiable Design Rule

`ArchetypeWorld` must have one logical owner thread at a time.

If systems run on a dedicated thread, then direct world mutation or direct world reads from the main thread must stop or be converted into one of:

- published snapshots
- command queues
- explicit synchronized handoff points

Do not let both render/UI and the simulation thread touch the world concurrently just because the code compiles.

## 3. Proposed Runtime Modes

### `MAIN_THREAD`

Behavior:

- current behavior
- caller invokes `updateGroup(...)`, `update(...)`, or `GameLoop.run()`
- the caller thread owns the world during those calls

Use cases:

- simple games
- tools
- deterministic profiling
- minimum latency path with fewer synchronization costs

### `DEDICATED_THREAD`

Behavior:

- a dedicated runtime thread owns the simulation loop
- render/UI/main thread submits commands and reads published snapshots
- systems still use the ECS worker pool for internal parallel queries if enabled

Use cases:

- apps with heavy rendering/UI work on the main thread
- future engine integration where ECS simulation should run independently
- experiments with decoupled sim/render pacing

## 4. API Shape

Introduce an explicit runtime enum, for example:

```java
public enum SystemThreadMode {
    MAIN_THREAD,
    DEDICATED_THREAD
}
```

Recommended API surface:

- `ECS.Builder.withSystemThreadMode(SystemThreadMode mode)`
- `ECS.Builder.withSimulationThreadName(String name)` optional
- `ECS.Builder.withFixedTickRate(float hz)` if the dedicated runtime owns the fixed loop

Runtime accessors:

- `ECS.systemThreadMode()`
- `ECS.startRuntime()` for dedicated-thread mode
- `ECS.stopRuntime()`
- `ECS.awaitRuntimeStop()` optional

Compatibility rule:

- `MAIN_THREAD` keeps current blocking and manual-update APIs working
- `DEDICATED_THREAD` should reject or clearly document direct `update*()` calls from non-owner threads

## 5. Architecture Split

### Option A. Minimal Wrapper Around Existing APIs

Keep:

- `SystemManager`
- `GameLoop`
- current synchronous system execution semantics

Add:

- `EcsRuntimeController`
- one dedicated thread that repeatedly calls `SystemManager.updateGroup(...)`

Pros:

- least invasive
- easiest path to first working version

Cons:

- `DemoMain` and overlay still need command/snapshot infrastructure

Recommendation:

- start here

### Option B. Rewrite Around Fully Asynchronous Runtime

Add:

- runtime-owned loop abstraction
- explicit command mailbox
- snapshot publication baked into runtime

Pros:

- cleaner long-term model

Cons:

- larger refactor
- higher risk

Recommendation:

- do not start here

## 6. Main Risks To Solve First

### World Ownership

Today the boid demo main thread does all of these directly:

- `simulation.stepFixed()`
- `simulation.resetBoids(...)`
- `simulation.collectWorldStats()`
- parameter changes through overlay

In dedicated-thread mode these must become either:

- commands sent to the sim thread
- or reads from published snapshots

### Render Snapshot Publication

Phase F already moved boid position upload onto a contiguous snapshot.

That solves only part of the problem.

We still need safe cross-thread publication for:

- boid positions
- simulation stats
- world stats
- benchmark stats

### Overlay / Input Commands

The UI currently mutates simulation state immediately.

Dedicated-thread mode needs a command queue for operations such as:

- pause/resume
- reset boids
- change steering mode
- change steering weights
- toggle benchmark

### Shutdown Semantics

Need a clean answer for:

- what happens if the window closes while the sim thread is mid-update
- how to drain pending commands
- how to close `ArchetypeWorld` only after the runtime thread stops

## 7. Phase Plan

### Phase A. Runtime Ownership Contract

Goal:

- document and enforce which thread owns ECS updates

Tasks:

- add `SystemThreadMode`
- add owner-thread checks in the new runtime layer
- decide which existing APIs remain legal in dedicated-thread mode

Deliverable:

- explicit threading contract in code and docs

Acceptance:

- misuse fails fast with a clear exception instead of silent races

### Phase B. `EcsRuntimeController`

Goal:

- introduce a small runtime wrapper that can run systems either on the caller thread or a dedicated thread

Tasks:

- add runtime state machine: `NEW`, `RUNNING`, `STOPPING`, `STOPPED`
- add dedicated simulation thread bootstrap/stop/join
- keep main-thread mode as a no-op wrapper

Deliverable:

- runtime controller owned by `ECS`

Acceptance:

- both modes can start and stop cleanly

### Phase C. Command Mailbox

Goal:

- stop UI/main thread from mutating simulation state directly in dedicated-thread mode

Tasks:

- add thread-safe command queue
- convert boid-demo controls to commands:
  - reset
  - pause
  - steering mode
  - steering params
  - benchmark triggers

Deliverable:

- simulation thread consumes commands before/after fixed-step work

Acceptance:

- overlay no longer directly calls world-mutating methods when in dedicated-thread mode

### Phase D. Snapshot Publication

Goal:

- let render/UI read published state without touching the world

Tasks:

- publish render positions snapshot
- publish simulation stats snapshot
- publish world stats snapshot on a controlled cadence
- use atomic swap or double-buffering

Deliverable:

- immutable read model for the main thread

Acceptance:

- render loop and overlay work without direct world reads in dedicated-thread mode

### Phase E. Boid Demo Integration

Goal:

- make the boid demo a real proving ground for both modes

Tasks:

- add a runtime mode selector to the demo config/overlay
- keep `MAIN_THREAD` as default
- add a dedicated-thread stress path
- ensure GLFW/OpenGL stays on the main thread

Deliverable:

- boid demo can run in either mode

Acceptance:

- switching mode changes only how simulation is scheduled, not visible behavior

### Phase F. ECS Facade Integration

Goal:

- expose this cleanly through `ECS.Builder`

Tasks:

- builder flag for `SystemThreadMode`
- lifecycle methods on `ECS`
- clarify interactions with `run()`, `stop()`, `createGameLoop(...)`, and manual `updateGroup(...)`

Deliverable:

- public API for runtime threading mode

Acceptance:

- users can choose mode without wiring internal classes manually

### Phase G. Profiling + Validation

Goal:

- confirm the new mode is functionally correct and worth keeping

Tasks:

- benchmark `MAIN_THREAD` vs `DEDICATED_THREAD`
- measure:
  - sim FPS
  - render FPS
  - input latency
  - command latency
- capture thread ownership assertions in tests

Deliverable:

- comparison data for both modes

Acceptance:

- dedicated-thread mode is stable and does not regress main-thread mode

## 8. Recommended Implementation Order

Do this in order:

1. Add `SystemThreadMode` and runtime controller shell.
2. Keep boid demo on `MAIN_THREAD` by default.
3. Add command mailbox.
4. Add published snapshots for stats and render.
5. Wire dedicated-thread mode into boid demo.
6. Only then expose the mode broadly through docs/examples.

Do not start by flipping boid demo to a background simulation thread without the mailbox and snapshot layers.

## 9. Validation Matrix

Minimum matrix:

- `MAIN_THREAD` + sequential steering
- `MAIN_THREAD` + parallel steering
- `DEDICATED_THREAD` + sequential steering
- `DEDICATED_THREAD` + parallel steering

For each:

- startup/shutdown
- reset from overlay
- benchmark mode
- stress preset
- no crash on window close

## 10. Exit Criteria

This track is complete when:

- `ECS` supports both thread modes explicitly
- main-thread mode remains the default and stays fast
- dedicated-thread mode has command queue + snapshot publication
- boid demo works in both modes without direct cross-thread world access
- profiling shows no accidental race-induced stalls or shutdown issues

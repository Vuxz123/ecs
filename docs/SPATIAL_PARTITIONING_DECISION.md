# Spatial Partitioning Decision For Boid Demo

This note records the design decision for boid spatial partitioning in the demo.

## Decision

Do not use `SharedComponent` values as the primary per-frame spatial partition for boids.

Use:

- regular unmanaged components for `Position3`, `Velocity3`, `Acceleration3`, `CellKey`
- a frame-local `SpatialHashGrid` snapshot for neighborhood search
- ECS queries to capture component data into the spatial hash once per simulation tick

## Why Not `SharedComponent` For `SpatialCell`

At first glance, grouping boids by shared spatial cell looks attractive because it would let queries run "by chunk" per region.

That approach is the wrong fit for this demo because boids cross cell boundaries constantly.

If `SpatialCell` is modeled as a `SharedComponent`, every cell change becomes a structural migration:

- the entity leaves one `ChunkGroup`
- the entity enters another `ChunkGroup`
- archetype/group bookkeeping runs every time the cell changes

For flocking, that means the benchmark stops measuring mostly "steering + neighborhood search" and starts measuring "structural churn from region reassignment".

That is not the story we want from this demo.

## What We Actually Want To Measure

The boid demo is meant to highlight:

- chunk-friendly ECS storage for component capture
- generated query execution
- parallel system/query execution
- fixed-timestep simulation stability
- render/snapshot publication

It is not meant to benchmark per-frame structural moves between chunk groups.

## Chosen Architecture

The current architecture stays:

1. `SpatialHashSystem`
   Captures `Position3`, `Velocity3`, and `CellKey` into a contiguous `SpatialHashGrid`.

2. `SteeringSystem`
   Reads from the spatial hash snapshot, not from shared-component chunk partitioning.

3. `MovementSystem`
   Integrates motion and publishes render positions.

4. `BoidRuntime`
   Publishes render/stats snapshots for main-thread rendering and UI.

This keeps ECS responsible for authoritative simulation state while using a purpose-built spatial index for the neighbor problem.

## Why This Is Better

Benefits of the snapshot + spatial hash approach:

- no per-frame structural migration just because a boid crosses a cell boundary
- explicit control over neighbor caps, sampling, and search heuristics
- predictable memory layout for neighborhood scanning
- clearer separation between ECS storage concerns and spatial-query concerns
- easier profiling because compute cost is not mixed with archetype churn

## When `SharedComponent` Still Makes Sense

`SharedComponent` values are still useful for coarse, stable partitions such as:

- `FlockId`
- `RegionId`
- `ZoneId`
- long-lived gameplay partitions that do not change every frame

If the demo later needs chunk filtering by space, the right hybrid is:

- use shared components for coarse, stable regions
- use `SpatialHashGrid` inside each region for fine dynamic neighbor search

Do not assign one shared value per dynamic fine-grained spatial cell.

## Practical Rule

For this demo:

- stable grouping -> `SharedComponent`
- dynamic fine spatial lookup -> `SpatialHashGrid`

That is the baseline going forward unless the demo goal changes from "flocking benchmark" to "structural migration benchmark".

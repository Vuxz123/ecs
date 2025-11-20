# ECS Architecture

Textual overview of how the modules, data layout, and execution pipeline fit together, with supporting details sourced from `IMPROVEMENTS.md`, `COMPONENT_SYSTEM.md`, `README_PARALLEL_SYSTEM.md`, `PARALLEL_QUERY_GUIDE.md`, and the QA docs. Release-by-release evolution lives in `docs/ROADMAP.md`; deep appendices stay in `docs/ADVANCED_GUIDE.md`. For a focused description of the annotation- and Panama-based component model, see `docs/COMPONENT_SYSTEM.md`. For the system lifecycle and scheduling model, see `docs/SYSTEMS.md`.

## 1. System Overview
- **ecs-core** – Hosts `ECS` facade, `ComponentManager`, `ArchetypeWorld`, `SystemManager`, and `GameLoop`.
- **ecs-processor** – Annotation processor that scans component classes and emits descriptors, handles, and `GeneratedComponents.registerAll(...)` glue.
- **ecs-test** – Regression and QA suites mirroring real gameplay scenarios.
- **ecs-benchmark** – JMH suite targeting SoA layout, SIMD loops, and parallel query throughput (see `PHASE2_COMPLETION.md`).
- **Root Gradle build** – Applies shared toolchain flags (preview/VECTOR/Panama), configures Maven publish via Vanniktech + `SonatypeHost.CENTRAL_PORTAL`, and normalizes signing files for Windows per the QA checklist.

## 2. Module Interaction
```
Application code → ECS facade → ArchetypeWorld ↔ ComponentManager
                                 ↓
                          SystemManager
                                 ↓
                            GameLoop
```
Build phase:
- `ecs-processor` inspects component classes → generates handles/descriptors → writes `GeneratedComponents` registrar.
Runtime phase:
- `ECS` builder auto-invokes the registrar, initializing `ComponentManager` before the world and system pipeline spin up.

## 3. Data Flow & Memory Layout
The world combines an archetype/chunk layout with three component storage paths: unmanaged (off-heap), managed (on-heap objects via tickets), and shared (per-chunk-group state).

### 3.1 Component metadata & registration
- All component types are registered with `ComponentManager`, which assigns a stable **component type id** and builds a `ComponentDescriptor`.
- Descriptors carry layout and management flags (e.g. `isManaged()`), coming from annotations described in `COMPONENT_SYSTEM.md`.
- At startup, `ECS.Builder` normally invokes `GeneratedComponents.registerAll(componentManager)` to register all known components; you can override this manually.

### 3.2 Archetypes, chunk groups, and chunks
- An **Archetype** represents a unique `ComponentMask` – a bitset of component type ids.
- For each archetype, the world maintains one or more **ChunkGroup**s keyed by a `SharedValueKey` (shared-component configuration).
- Each **ArchetypeChunk** in a group holds a fixed-capacity slice of entities that share the same component mask + shared key.

Conceptually:
```text
Archetype(mask = {Position, Velocity, Health})
  └─ ChunkGroup(sharedKey)
       ├─ Chunk 0
       │    ├─ unmanaged arrays per component type
       │    ├─ managed ticket arrays per managed type
       │    └─ entityIds[]
       └─ Chunk 1
            ...
```

### 3.3 Unmanaged (off-heap) components
Unmanaged components are value-like and live entirely in foreign memory:
- `ComponentDescriptor.isManaged() == false`.
- For each entity + unmanaged component type, the world allocates a `MemorySegment` using `ComponentManager.allocate(componentClass, arena)`.
- `ArchetypeChunk` stores these segments in per-type/per-entity slots via `setComponentData(location, componentIndex, segment)`.

Logically, per chunk:
```text
unmanaged[componentTypeIndex][entityIndex] -> MemorySegment
```
The actual field layout inside each segment (SoA/struct-like) is defined by the descriptor and annotation config.

### 3.4 Managed (on-heap) components
Managed components are full Java objects stored indirectly:
- Marked `@Component.Managed` and reported as `isManaged() == true` in `ComponentDescriptor`.
- Instances live in a global `ManagedComponentStore`.
- When you call `addComponent(entityId, myManagedComponent)`, the store returns an integer **ticket**:
  1. The entity is moved to a new archetype/ChunkGroup whose mask includes that component type id.
  2. The destination chunk writes the ticket into a per-type ticket array using `setManagedTicket(managedTypeIndex, indexInChunk, ticket)`.

Per chunk, this is conceptually:
```text
managedTickets[managedTypeIndex][entityIndex] -> int ticket
ManagedComponentStore[ticket] -> actual object instance
```
Systems resolve tickets back to objects when they need to operate on managed state.

### 3.5 Shared components and `SharedComponentStore`
Some state is shared across many entities in the same chunk group:
- A `SharedValueKey` identifies a particular combination of shared component values.
- `SharedComponentStore` holds the actual shared data bundles.
- `ArchetypeWorld` uses the key when creating/finding a `ChunkGroup`, so all entities in that group implicitly share those values.

At a high level:
```text
SharedComponentStore[sharedKey] -> shared data
Archetype(mask)
  └─ ChunkGroup(sharedKey)  // all chunks here see the same shared data
```

### 3.6 Entity lifecycle
The entity lifecycle ties these pieces together:
1. **Creation** (`createEntityWithComponents`)
   - Allocate a new entity id.
   - Build a `ComponentMask` from the requested component classes.
   - Ask `ArchetypeManager` for an archetype matching that mask.
   - Get or create a `ChunkGroup` for the default `SharedValueKey` (no explicit shared data yet).
   - Add the entity to a chunk via `ChunkGroup.addEntity`, which returns a `ChunkLocation`.
   - For each **unmanaged** component: allocate a zeroed `MemorySegment` and wire it into the chunk.
   - For **managed** components: tickets default to `-1` until an instance is attached.

2. **Structural changes** (add/remove component)
   - Compute a new `ComponentMask` that includes/excludes the component's type id.
   - Call `moveEntityToArchetype(...)` to move the entity between archetypes while preserving its `SharedValueKey`.
   - For unmanaged components, copy or assign the relevant `MemorySegment` into the new chunk slot.
   - For managed components, update tickets and, if removing, release the old ticket from `ManagedComponentStore`.

3. **Shared component changes**
   - Changing shared state effectively means moving the entity to another `ChunkGroup` with a different `SharedValueKey`.

This design allows:
- Dense per-archetype storage for predictable iteration order.
- Off-heap value storage for tight loops.
- On-heap managed objects when you need rich Java semantics.
- De-duplicated shared state without duplicating memory across thousands of entities.

## 4. Execution Pipeline & GameLoop
- Systems live inside `SystemGroup`s (INPUT, SIMULATION, RENDER, custom). Groups carry rank/priority to preserve deterministic ordering (`README_PARALLEL_SYSTEM.md`).
- `SystemManager.registerPipelineSystem(system, group)` wires systems into each group; dependency injection populates generated query handles.
- `GameLoop` (default 60 Hz) ticks groups sequentially each frame; `ECS.createGameLoop(targetHz)` lets clients embed loops at custom rates or threads (virtual or platform).

```
[GameLoop tick]
  → INPUT systems
  → SIMULATION systems (optionally parallel per query)
  → RENDER systems
```
- Parallel execution: queries call `.parallel(...)` (see Section 5) to fan out chunk work items across a ForkJoinPool. Systems must keep per-thread state isolated or rely on concurrent primitives.

## 5. Parallel Query Model
Derived from `PARALLEL_QUERY_GUIDE.md`.
1. Filter archetypes by required/optional components.
2. Snapshot volatile chunk arrays (`getChunksSnapshot()`).
3. Flatten into `ChunkWorkItem[]` and submit to a ForkJoin worker.
4. Within each chunk, process entities sequentially to maximize cache hits, invoking the thread-safe consumer for each entity.

Performance defaults:
- Break-even entity count ~1k (below that, stay sequential).
- Fusing Vector API operations (`FloatVector.fma`) yields ~3× speedups on 10k entities (data from `IMPROVEMENTS.md`).
- Consumers must use atomics or thread-local batching to avoid contention (see QA checklist “Parallel Safety”).

## 6. Component Lifecycle & Codegen
1. **Registration** – Builder auto-runs `GeneratedComponents.registerAll(componentManager)` unless `noAutoRegistration()` is set. Manual overrides call `registerComponent(Class<?>)`.
2. **Entity creation** – `ArchetypeWorld` picks/creates a chunk based on component signature, assigning contiguous slots per component column.
3. **System execution** – Generated handles (e.g., `PositionHandle`) provide zero-copy getters/setters inside query lambdas, avoiding reflection and boxing.

## 7. QA, Publishing, and Tooling
- QA gates (`QA_QC_CHECKLIST.md`): verify AP output, SoA layout integrity, parallel query determinism, and JVM args (`--enable-preview --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector`).
- Test suites (`QA_QC_TEST_CASES.md`, `QA_QC_TEST_SUITE.md`) map to `ecs-test`; run via Gradle so all flags apply consistently.
- Publishing: root `build.gradle.kts` configures Vanniktech with `SonatypeHost.CENTRAL_PORTAL`, normalized `secring.gpg` path, and coordinates `io.github.vuxz123:{ecs-core|ecs-processor}:0.1.+`.
- Benchmarks: `ecs-benchmark` JMH scenarios guard SoA/SIMD regressions; results feed into Phase completion reports.

## 8. Reference Map
- `docs/ADVANCED_GUIDE.md` – Expanded discussions + appendices (parallel systems, QA artifacts, improvement logs).
- `docs/ROADMAP.md` – Milestone history (Phase 1/2) and rolling backlog for future architectural shifts.
- `PHASE1_COMPLETION.md`, `PHASE2_COMPLETION.md` – Deep historical records referenced by the roadmap.
- Legacy design docs (`IMPROVEMENTS.md`, `COMPONENT_SYSTEM.md`, `README_PARALLEL_SYSTEM.md`, `PARALLEL_QUERY_GUIDE.md`, `QA_QC_*`) remain the canonical sources for the summaries above; edit them first, then refresh this file.

> Update this document whenever foundational architecture changes. For chronological context or upcoming work, rely on the roadmap; for exhaustive technical detail, use the advanced guide appendices.

# Systems

This guide explains how systems work in the ECS: what they are, how they are scheduled through groups, how they access data, and how they integrate with the `GameLoop`.

## 1. What Is a System?

A **System** is a unit of behavior that runs on every tick and operates over entities that match some component pattern.

In code, systems implement the `ISystem` interface:

```java
public interface ISystem {
    void onAwake(ArchetypeWorld world);   // called once when registered
    void onUpdate(float deltaTime);       // called every tick while enabled
    void onDispose();                     // called when the world is closing or system is removed

    boolean isEnabled();                  // current enabled state
    void setEnabled(boolean enabled);     // toggle participation in updates
}
```

Lifecycle:
- **onAwake** – invoked once when the system is registered; you can cache references, set up queries, etc.
- **onUpdate** – invoked once per tick by the `SystemManager`/`GameLoop` if the system is enabled.
- **onDispose** – invoked when the ECS world is closing or the system is being removed.

## 2. System Groups

Systems are organized into **SystemGroup**s that represent phases of the frame:

```java
public record SystemGroup(String name, int priority, UpdateMode mode) implements Comparable<SystemGroup> {
    public static final SystemGroup INPUT      = new SystemGroup("Input",      0,    UpdateMode.VARIABLE);
    public static final SystemGroup SIMULATION = new SystemGroup("Simulation", 1000, UpdateMode.FIXED);
    public static final SystemGroup PHYSICS    = new SystemGroup("Physics",    2000, UpdateMode.FIXED);
    public static final SystemGroup RENDER     = new SystemGroup("Render",     3000, UpdateMode.VARIABLE);
    public static final SystemGroup CLEANUP    = new SystemGroup("Cleanup",    4000, UpdateMode.VARIABLE);
}
```

- `name` – label for debugging/logging.
- `priority` – lower values run earlier.
- `mode` – `FIXED` or `VARIABLE` (used by `SystemManager` and `GameLoop` to decide which groups participate in fixed-step updates).

You can define your own groups; they will be sorted alongside the built-ins by `priority`, then by `name`.

## 3. Registering Systems

The easiest way to register systems is via the `ECS` builder:

```java
try (var ecs = ECS.builder()
        .addSystem(new InputSystem(), SystemGroup.INPUT)
        .addSystem(new MovementSystem(), SystemGroup.SIMULATION)
        .addSystem(new RenderSystem(), SystemGroup.RENDER)
        .build()) {
    ecs.run();
}
```

Builder methods:
- `addSystem(ISystem system)` – registers into `SystemGroup.SIMULATION` by default.
- `addSystem(ISystem system, SystemGroup group)` – registers into a specific group.

Internally this calls:

```java
SystemManager sysMgr = new SystemManager(world);
sysMgr.registerPipelineSystem(system, group);
```

### 3.1 What SystemManager Does

`SystemManager` is responsible for:
- Keeping a map of `SystemGroup -> List<ISystem>`.
- Calling a generated injector for each system (if present) to wire up queries.
- Managing group ordering (fixed vs variable) and running systems each frame.

Key methods:

```java
public <T extends ISystem> T registerSystem(T system);              // default SIMULATION
public <T extends ISystem> T registerPipelineSystem(T system, SystemGroup group);
public void updateGroup(SystemGroup group, float deltaTime);       // run one group
public void update(float deltaTime);                               // run all groups
```

During registration:
1. The manager tries to invoke a generated injector named `YourSystem__QueryInjector`.
2. `onAwake(world)` is called on the system.
3. The system is placed into the appropriate group list and group ordering is recomputed.

## 4. Execution Order & GameLoop

The `GameLoop` drives systems via `SystemManager`:

- **Per tick** it will:
  1. Run all `FIXED`-mode groups in priority order.
  2. Run all `VARIABLE`-mode groups in priority order.

Conceptually:

```text
[GameLoop tick]
  → INPUT   (VARIABLE)  systems
  → SIMULATION (FIXED)  systems
  → PHYSICS  (FIXED)    systems
  → RENDER   (VARIABLE) systems
  → CLEANUP  (VARIABLE) systems
```

From the `ECS` facade you can:
- Call `ecs.run()` – start the default fixed-timestep loop (blocks until `ecs.stop()` is called).
- Create a custom loop: `GameLoop loop = ecs.createGameLoop(targetHz); loop.run();`.
- Manually step a group: `ecs.updateGroup(SystemGroup.SIMULATION, dt);`.

## 5. Data Access Inside Systems

Systems typically operate over entities via **generated queries** driven by the `@Query` annotation on private methods. The primary model looks like the demo systems in `ecs-test` (`MovementSystem`, `HealthRegenerationSystem`, `MixedUnmanagedAndManagedSystem`, `TeamFilterSystem`):

- You declare a private method annotated with `@Query` that describes the component pattern and execution mode.
- You provide a `fieldInject` name; the annotation processor generates an injector that initializes that field with an `IGeneratedQuery` instance when the system is registered.
- In `onUpdate`, the system calls `generatedQuery.runQuery()` to process all matching entities.

This keeps the hot path very simple and lets the generated code own the heavy query construction and wiring.

### 5.1 `@Query` + `IGeneratedQuery` (Recommended)

A typical pattern, as in `MovementSystem` and `HealthRegenerationSystem`:

- The system has a field of type `IGeneratedQuery` (for example, `movingEntities`, `healthyEntities`).
- A private `@Query`-annotated method declares:
  - `fieldInject = "movingEntities"`.
  - `mode = ExecutionMode.SEQUENTIAL` or `ExecutionMode.PARALLEL`.
  - A `with = { ... }` list describing required components.
- At registration time, the generated injector:
  - Builds and caches the underlying query/builder once.
  - Stores it in the target field.
- On each `onUpdate`, the system just checks `if (movingEntities != null)` and calls `movingEntities.runQuery()`.

Some generated query fields also implement `IQueryBuilder` (see `TeamFilterSystem`):
- The field is still created once and cached by the injector.
- At runtime you can adjust light filters on that builder, such as `withShared(new TeamShared("A"))`, then call `build().runQuery()`.
- The heavy part (wiring up the underlying query over archetypes and components) is still done once; per-frame you only tweak the filter state and run.

As a rule of thumb:
- Prefer `@Query` + `IGeneratedQuery` for normal game and simulation logic.
- Let the generated code manage query creation and caching; your systems should mostly just call `runQuery()` in `onUpdate`.

### 5.2 Manual `ArchetypeQuery` Usage (Advanced / Low-Level)

For specialized needs you can manually build and run queries against `ArchetypeWorld` using `ArchetypeQuery` and `world.query()`. This is a **low-level** API that is not used in the standard demo systems and is intended for advanced integrations, experiments, or tooling.

If you do use it, follow these rules:
- Build and cache an `ArchetypeQuery` once (e.g., in the constructor or `onAwake`).
- Reuse that cached query inside `onUpdate` to iterate matching entities.
- Avoid rebuilding the same query on every frame or inside inner loops.

**Query construction cost.** Building a query (whether via `@Query`-generated code or via the `ArchetypeQuery` builder) computes archetype filters and internal data structures. This is relatively heavy compared to a plain loop. You should almost always:
- Build queries once per system (for example, during registration or `onAwake`).
- Reuse those query instances on every tick.
- Only adjust lightweight filters (like shared/team selection) in hot paths, not the fundamental component mask.

For deeper details on queries and data layout, see:
- `docs/COMPONENT_SYSTEM.md`
- `docs/ARCHITECTURE.md`
- `docs/ADVANCED_GUIDE.md` (parallel queries and best practices)

## 6. Parallel Work in Systems

Systems can use the **parallel query model** described in `PARALLEL_QUERY_GUIDE.md` and `README_PARALLEL_SYSTEM.md` by calling `forEachParallel` on queries.

```java
var query = world.query()
        .with(Position.class)
        .with(Velocity.class)
        .build();

AtomicInteger processed = new AtomicInteger(0);

query.forEachParallel((entityId, handles, archetype) -> {
    // This code may run on worker threads – must be thread-safe
    processed.incrementAndGet();

    ComponentHandle pos = handles[0];
    ComponentHandle vel = handles[1];
    // Apply movement or other CPU-heavy logic here
});
```

Rules of thumb:
- Treat the `forEachParallel` consumer body as multi-threaded code:
  - Use `Atomic*` or concurrent collections for shared counters or maps.
  - Prefer per-thread batching (e.g., thread-local lists) over heavy synchronization.
- Only use parallel queries when you have enough work (roughly 1k+ entities) or CPU-heavy logic.

For full design details and performance tips, see `docs/ADVANCED_GUIDE.md` and the parallel query guides listed there.

## 7. Patterns & Best Practices

**Do:**
- Keep each System focused on a single responsibility (movement, rendering, health regen, etc.).
- Use groups to sequence phases (input → simulation → physics → render).
- Use the query API instead of hand-rolled loops over entity ids.

**Avoid:**
- Blocking I/O (disk, network) in `onUpdate` for high-frequency Systems.
- Storing large mutable global state shared between Systems without synchronization.
- Mutating component structure (adding/removing components) inside tight per-entity loops unnecessarily; batch structural changes when possible.

With these guidelines, Systems remain easy to reason about and scale well as you add more behavior to your game or simulation.

## 8. Batching Structural Changes with EntityCommandBuffer

When a system wants to create/destroy entities or add/remove components while it is iterating over entities, doing those structural changes directly inside the query callback can be problematic, especially for parallel systems. To keep iteration stable and avoid complex synchronization, you should batch these operations via an `EntityCommandBuffer`.

### 8.1 General Idea

Instead of:
- Calling `world.createEntity(...)`, `world.addComponent(...)`, `world.removeComponent(...)`, or `world.destroyEntity(...)` directly inside the callback of `@Query` / `runQuery()`.

Do:
- Record (enqueue) those operations into an `EntityCommandBuffer` while the query is running.
- After `generatedQuery.runQuery()` (or the entire frame) finishes, call a `commandBuffer.flush(world)`-like function to apply all changes at once.

Benefits:
- **Structural Stability**: doesn't break layout / iterator of `ArchetypeWorld` while the query is iterating entities.
- **Thread-safe with Parallel**: `ExecutionMode.PARALLEL` systems can let each worker thread write commands to its own buffer and then merge, instead of directly touching the world from multiple threads.
- **Easier reasoning**: clearly separates the "read + decide" and "commit structural changes" phases.

### 8.2 When to Use EntityCommandBuffer

Prefer using `EntityCommandBuffer` when:
- Your system is using `ExecutionMode.PARALLEL` and wants to:
  - Spawn additional entities from inside the query.
  - Add / remove components of the entities being processed.
  - Destroy entities based on conditions.
- The system logic is complex, with many rules potentially touching the same entity / component in one frame.
- You want to batch statistics / logs about structural changes (how many entities were created, how many components were removed, etc.) before committing.

### 8.3 Typical Processing Flow in System

A typical flow with `EntityCommandBuffer` looks like this:

1. System holds a buffer (or receives one from ECS context) to use for each frame.
2. In `onUpdate(float dt)`:
   - Reset / clear the buffer for the new frame.
   - Call `generatedQuery.runQuery()`. Inside the `@Query` callback:
     - **Only read** components and make decisions.
     - Instead of calling `world.add/remove/destroy` directly, just enqueue the corresponding commands to the buffer.
3. After `runQuery()` finishes:
   - Call `buffer.flush(world)` (or equivalent API) to apply all commands to the `ArchetypeWorld`.

With parallel systems:
- Each thread can use its own buffer (thread-local) or `EntityCommandBuffer` that supports internal batching.
- After the query finishes, ECS can merge the batches and apply them on a single thread (or at a safe sync point) to ensure consistency.

### 8.4 Best Practices for Batching

- **Don't modify the world directly in hot loop queries**:
  - In the `@Query` callback, only read data and enqueue commands to the buffer.
- **Clearly separate the two phases**:
  - Phase 1: `runQuery()` – read state, decide what needs to change, push commands to the buffer.
  - Phase 2: `flush()` – commit structural changes (create/destroy/add/remove) to the `ArchetypeWorld`.
- **Reduce order dependencies**:
  - Design commands so that the results don't depend too much on the exact order in the buffer (unless the engine guarantees apply order).
- **Combine with query caching**:
  - Continue to follow the rule: queries are built and cached once; each frame only lightweight conditions are changed (e.g., shared/team), use queries for reading, and use buffers for writing.

For more details on structural change costs and how to optimize memory layout, see `docs/COMPONENT_SYSTEM.md` and the Performance section in `docs/ADVANCED_GUIDE.md`.
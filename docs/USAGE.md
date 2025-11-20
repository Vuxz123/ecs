# ECS Usage Guide

Detailed walkthrough for configuring the facade, wiring systems, and running the built-in `GameLoop`. For architecture dives, see `docs/ADVANCED_GUIDE.md`.

## 1. Project Setup
1. Add dependencies from `README.md` (floating `0.1.+` stays current).
2. Enable annotation processing so `ecs-processor` emits handles + `GeneratedComponents`.
3. Run `gradlew build` once to seed generated sources before launching samples.

```kotlin
plugins { id("application") }
dependencies {
    implementation("io.github.vuxz123:ecs-core:0.1.+")
    annotationProcessor("io.github.vuxz123:ecs-processor:0.1.+")
}
```

> IntelliJ IDEA: Settings → Build → Compiler → Annotation Processors → *Enable*. Other IDEs need Gradle sync so generated sources appear in classpath.

## 2. Builder Recipes
`ECS.builder()` composes the ComponentManager, ArchetypeWorld, and SystemManager.

| Builder hook | Use case |
| --- | --- |
| `addSystem(system)` | Register into `SystemGroup.SIMULATION` (default fixed-step).
| `addSystem(system, group)` | Route to other groups (`INPUT`, `RENDER`, custom).
| `registerComponent(clazz)` | Manual component wiring (pairs well with `noAutoRegistration()`).
| `noAutoRegistration()` | Skip `GeneratedComponents.registerAll` when you need explicit ordering or AP is offline.

Example:
```java
var ecs = ECS.builder()
        .registerComponent(PositionComponent.class)
        .registerComponent(VelocityComponent.class)
        .addSystem(new PhysicsSystem())
        .addSystem(new RenderSystem(), SystemGroup.RENDER)
        .build();
```

Most systems in this project follow the same pattern as the demo classes in `ecs-test` (`MovementSystem`, `HealthRegenerationSystem`, `MixedUnmanagedAndManagedSystem`, `TeamFilterSystem`):
- A field of type `IGeneratedQuery` (optionally also `IQueryBuilder`) is declared on the system.
- A private method annotated with `@Query` uses `fieldInject` to point at that field and describes required components and `ExecutionMode`.
- When you call `ECS.builder().addSystem(...)`, the `SystemManager` will run the generated injector for each system at registration time, constructing and caching the appropriate query object.
- In `onUpdate`, the system simply checks for null and calls `generatedQuery.runQuery()` (and, when needed, applies lightweight per-frame filters like `withShared(TeamShared)` on a cached builder before running).

You rarely need to construct `ArchetypeQuery` manually unless you are doing advanced or integration work; the normal flow is entirely driven by `@Query` + generated `IGeneratedQuery` fields.

### 2.1 System API Demo (How it Fits Together)

The `SystemAPIDemo` in `ecs-test` shows this end-to-end:
- It creates an `ECS` instance via `ECS.builder()`.
- It registers systems like `MovementSystem`, `HealthRegenerationSystem`, `MixedUnmanagedAndManagedSystem`, and `TeamFilterSystem`.
- It registers additional components (`NameComponent`, `TeamShared`) that those systems depend on.
- When `build()` is called, `SystemManager`:
  - Runs the generated injectors for each system.
  - Initializes their `IGeneratedQuery` / `IQueryBuilder` fields based on the `@Query` annotations.
- The demo then creates 10,000 entities using the `ecs.createEntity(...)` API and initializes unmanaged components via the typed-handle `addComponent` helpers.
- Finally, it calls `systemManager.update(deltaTime)`, which runs all enabled systems in group order; each system’s `onUpdate` simply invokes its generated queries (`runQuery()`) to apply game logic.

You can use `SystemAPIDemo` as a reference template for wiring your own systems and seeing how `@Query`, generated queries, and the `ECS` facade work together.

Custom phases are simple records:
```java
public static final SystemGroup UI = new SystemGroup("UI", 1500, UpdateMode.VARIABLE);
```
Register systems against `UI` to blend them into the pipeline.

## 3. Lifecycle & GameLoop
The facade owns a default 60 Hz `GameLoop`.

Blocking loop:
```java
try (var ecs = ECS.builder()
        .addSystem(new SimulationSystem())
        .addSystem(new RenderSystem(), SystemGroup.RENDER)
        .build()) {
    ecs.run();
}
```

Custom rate / embedding:
```java
var ecs = ECS.builder().addSystem(new SimulationSystem()).build();
GameLoop loop = ecs.createGameLoop(120f);
Thread loopThread = Thread.ofVirtual().start(loop::run);
// ... later
loop.stop();
loopThread.join();
```

Manual stepping (tests, scripts):
```java
float dt = 1f / 30f;
ecs.updateGroup(SystemGroup.SIMULATION, dt);
ecs.updateGroup(SystemGroup.RENDER, dt);
```

## 4. Entities & Components

Creating entities chooses optimal overloads before falling back to `createEntityWithComponents`:
```java
int enemy = ecs.createEntity(Position.class, Velocity.class, Health.class);
```

Zero-copy initialization uses generated handles:
```java
ecs.addComponent(enemy, Health.class, (HealthHandle h) -> h.setValue(100));
```
If `GeneratedComponents` is missing, rebuild with AP enabled.

## 5. Testing, Benchmarks, Publishing
- Tests: `gradlew test` (preview flags wired via root Gradle script).
- Benchmarks: `gradlew :ecs-benchmark:jmh`.
- Local publish: `gradlew publishToMavenLocal`.
- Maven Central: `gradlew publishAllPublicationsToMavenCentralRepository` (Vanniktech plugin config + `SonatypeHost.CENTRAL_PORTAL` already in root build).

Publishing tips:
- `secring.gpg` path normalized in root script (`replace("\\", "/")`).
- Use `sonatypeSnapshots()` in consuming apps if you need pre-release bits.
- Keep version floating (`0.1.+`) so updates land automatically.

## 6. Troubleshooting
- `GeneratedComponents` missing → AP disabled or build never run.
- Foreign-memory flags missing → run via Gradle tasks (they inject `--enable-preview --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector`).
- Systems idle → verify `ISystem` implementation, builder registration, and `GameLoop` is running or groups are stepped manually.
- Publish `file://` errors → allow root Gradle script to resolve URIs; avoid manual `file://` entries.

## 7. Beyond the Basics
- See `docs/ADVANCED_GUIDE.md` for the query model (including `@Query`-based systems, generated `IGeneratedQuery`/`IQueryBuilder` usage, and advanced manual `ArchetypeQuery` APIs), parallel queries, QA/QC process, performance tuning, and historical appendices.
- Track milestones in `docs/ROADMAP.md` (updated each milestone with backlog items).
- Explore `ecs-test` and `ecs-benchmark` for canonical patterns and performance baselines.

# ECS Usage Guide

This document complements `README.md` with deeper guidance on how to assemble an Archetype world, wire systems, and operate the built-in `GameLoop`. It starts tutorial-style and ends with reference sections you can dip into later.

## 1. Project Setup (quick tutorial)
1. Add the dependencies shown in `README.md` (use floating `0.1.+` or the latest SNAPSHOT).
2. Enable annotation processing in your IDE so `ecs-processor` can emit descriptors/handles.
3. Build once (`gradlew build`) to force `GeneratedComponents` generation before running samples.

```kotlin
// settings already include all modules; this is the typical app module snippet
plugins { id("application") }
dependencies {
    implementation("io.github.vuxz123:ecs-core:0.1.+")
    annotationProcessor("io.github.vuxz123:ecs-processor:0.1.+")
}
```

> **IDE tips:** in IntelliJ IDEA enable *Annotation Processing* (Settings → Build → Compiler → Annotation Processors) and add `ecs-processor` to the module dependencies. In VS Code or Eclipse, ensure the Gradle project is synced so `GeneratedComponents` lands before you run demos.

## 2. Builder Reference
`ECS.builder()` orchestrates the ComponentManager, ArchetypeWorld, and SystemManager. The common options are:

| Method | When to use |
| --- | --- |
| `addSystem(system)` | Register into `SystemGroup.SIMULATION` (fixed step). |
| `addSystem(system, group)` | Target another group such as `SystemGroup.INPUT` or a custom instance. |
| `registerComponent(clazz)` | Manually register components (usually combined with `noAutoRegistration()`). |
| `noAutoRegistration()` | Disable `GeneratedComponents.registerAll` if you want explicit ordering or AP is unavailable. |

Example mix-and-match:
```java
ECS ecs = ECS.builder()
        .noAutoRegistration()
        .registerComponent(PositionComponent.class)
        .registerComponent(VelocityComponent.class)
        .addSystem(new PhysicsSystem())
        .addSystem(new RenderSystem(), SystemGroup.RENDER)
        .build();
```

Need custom phases? `SystemGroup` is just a record defined in `ecs-core/src/main/java/com/ethnicthv/ecs/core/system/SystemGroup.java`. Instantiate your own:
```java
public static final SystemGroup UI = new SystemGroup("UI", 1500, UpdateMode.VARIABLE);
```
Register systems against that instance to weave it into the pipeline order you want.

## 3. Lifecycle & GameLoop
The facade now owns a default `GameLoop` (60 Hz). Blocking loop:
```java
try (var ecs = ECS.builder()
        .addSystem(new SimulationSystem())
        .addSystem(new RenderSystem(), SystemGroup.RENDER)
        .build()) {
    ecs.run(); // blocks until ecs.stop() is called on another thread
}
```
Custom rate or embedding in another engine:
```java
var ecs = ECS.builder().addSystem(new SimulationSystem()).build();
GameLoop loop = ecs.createGameLoop(120f);
Thread loopThread = Thread.ofVirtual().start(loop::run);
// ... later
loop.stop();
loopThread.join();
```
Need fine-grained control (tests, scripted scenarios):
```java
float dt = 1f / 30f;
ecs.updateGroup(SystemGroup.SIMULATION, dt);
ecs.updateGroup(SystemGroup.RENDER, dt);
```

## 4. Working with Entities & Components
Creating entities automatically selects the right overload up to six component classes and falls back to `createEntityWithComponents` beyond that.
```java
int enemy = ecs.createEntity(Position.class, Velocity.class, Health.class);
```
Zero-copy initialization uses the generated handle type:
```java
ecs.addComponent(enemy, Health.class, (HealthHandle handle) -> handle.setValue(100));
```
Handles come from AP output; if you see `GeneratedComponents` warnings, re-run the build so the processor emits them.

## 5. Testing, Benchmarks, and Publishing
- **Unit/Integration tests:** `ecs-test` contains demos; run everything via `gradlew test`. Ensure JVM args include `--enable-preview --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector` (root `build.gradle.kts` wires these automatically for Gradle tasks).
- **Benchmarks:** `ecs-benchmark` ships with JMH harnesses (`gradlew :ecs-benchmark:jmh`).
- **Local publishing:** `gradlew publishToMavenLocal` installs artifacts to `~/.m2`. Root `build.gradle.kts` configures Vanniktech Maven Publish with `SonatypeHost.CENTRAL_PORTAL` and normalizes the `secring.gpg` path.

**Publishing FAQ**
- *Where do staging repos live?* The plugin derives them automatically; you just run `gradlew publishAllPublicationsToMavenCentralRepository` and the root script maps Windows paths via `rootProject.file("secring.gpg")`.
- *Do I need `SonatypeHost` imports in subprojects?* No—root `build.gradle.kts` already imports `com.vanniktech.maven.publish.SonatypeHost` and applies the setting to `ecs-core` and `ecs-processor`.
- *How about snapshots?* Point Gradle to `sonatypeSnapshots()` in your consuming project; this repo intentionally leaves the version as `0.1.+` so future updates drop in without manual bumps.

## 6. Troubleshooting Checklist
- **`GeneratedComponents` missing:** Annotation processing disabled or build not run—enable AP and run `gradlew build` once.
- **Foreign memory errors:** Re-run with the JVM flags mentioned above; tests already include them.
- **Windows URI errors during publish:** Do not hard-code `file://` paths; rely on the root publish config which calls `rootProject.file("secring.gpg").absolutePath.replace("\\", "/")` and lets the plugin resolve staging directories.
- **Systems not firing:** Ensure they implement `ISystem`, call `SystemManager.registerPipelineSystem` via the builder (with proper group), and verify `GameLoop` is running.

## 7. Where to go next
- Re-read `README.md` for a concise overview and copy-friendly dependency snippets.
- Inspect `ecs-core/src/main/java/com/ethnicthv/ecs/ECS.java` for the full facade API, especially the `Builder` internals if you need custom wiring.
- Dive into `ecs-test` demos for concrete, runnable patterns and expand from there.

## 8. Coming soon
Looking for internal design notes? A forthcoming `docs/architecture.md` will diagram Archetype layout, memory arenas, and the annotation-processor flow. Ping the repo issues if you need it sooner.

# EthnicTHV ECS

High-performance Entity Component System for Java 25/Project Panama. Archetype storage, annotation-driven handles, and a built-in fixed-timestep `GameLoop` power modern gameplay and simulation workloads.

## Modules at a Glance
- `ecs-core`: Facade (`ECS`), archetype world, system manager, GameLoop
- `ecs-processor`: Annotation processor that emits descriptors, handles, and `GeneratedComponents`
- `ecs-test`: Reference scenarios + regression suites
- `ecs-benchmark`: JMH harnesses for SoA, SIMD, and query performance

## Install
Use floating versions (`0.1.+`) so you automatically pick up future drops. Snapshots live on Sonatype if you need them earlier.

```kotlin
// build.gradle.kts
repositories { mavenCentral() }
dependencies {
    implementation("io.github.vuxz123:ecs-core:0.1.+")
    annotationProcessor("io.github.vuxz123:ecs-processor:0.1.+")
}
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>io.github.vuxz123</groupId>
  <artifactId>ecs-core</artifactId>
  <version>0.1.+</version>
</dependency>
<dependency>
  <groupId>io.github.vuxz123</groupId>
  <artifactId>ecs-processor</artifactId>
  <version>0.1.+</version>
  <scope>provided</scope>
</dependency>
```

## Quickstart

```java
try (var ecs = ECS.builder()
        .addSystem(new PhysicsSystem())
        .addSystem(new RenderSystem(), SystemGroup.RENDER)
        .build()) {
    int player = ecs.createEntity(PositionComponent.class, VelocityComponent.class);
    ecs.addComponent(player, HealthComponent.class, handle -> handle.setValue(100));
    ecs.run(); // blocking loop (default 60 Hz)
}
```

## Performance Snapshot

On the reference machine used for our published benchmarks (`results-11-52-14-11-2025.txt`):

- **1,000,000 entities created in ~151 ms.**
- **Full read/write passes over 1,000,000 entities in ~13–15 ms.**
- **Parallel queries ~3.8× faster than sequential** on large worlds (1,000,000 entities: ~23.6 ms → ~6.2 ms).
- **Batched structural changes ~1.6–1.7× faster** than per-entity add/remove at 100,000 entities.
- **EntityCommandBuffer up to ~10× faster** than per-entity mutation in heavy workloads at 10,000 entities.

These numbers are measured from the real `ecs-benchmark` module against the same APIs you use. See `docs/BENCHMARKS.md` for tables and details.

## Feature Highlights
- Archetype + True SoA layout with zero-copy component handles (see `IMPROVEMENTS.md`)
- Annotation processor drives codegen + automatic registration via `GeneratedComponents`
- System groups (`INPUT`, `SIMULATION`, `RENDER`, …) managed through `SystemManager`
- Built-in `GameLoop` with `run`, `stop`, and `createGameLoop(targetHz)` helpers
- Panama/VECTOR ready: preview flags pre-wired in Gradle for builds, tests, and benchmarks

## Documentation Map
- [`docs/USAGE.md`](docs/USAGE.md): IDE setup, builder recipes, GameLoop integration, publishing/testing tips
- [`docs/SYSTEMS.md`](docs/SYSTEMS.md): System lifecycle (`ISystem`), groups, scheduling, data access, and GameLoop integration
- [`docs/COMPONENT_SYSTEM.md`](docs/COMPONENT_SYSTEM.md): Component annotations, Panama memory layout, ComponentManager/Descriptor/Handle usage
- [`docs/ADVANCED_GUIDE.md`](docs/ADVANCED_GUIDE.md): Deep dives on parallel queries, memory layout, QA/QC, and tuning (appendices reference legacy design docs)
- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md): Snapshot of real benchmark numbers (scaling, parallel queries, batched structural changes, `EntityCommandBuffer`) using the `ecs-benchmark` module
- [`docs/ROADMAP.md`](docs/ROADMAP.md): Completed milestones, current goals, and rolling backlog updated at the end of every milestone

Need more? Browse `ecs-test` for runnable demos or open an issue if you want walkthroughs on additional topics.

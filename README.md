# EthnicTHV ECS

High-performance Entity Component System for Java 25/Project Panama with annotation-driven codegen, archetype storage, and a fixed-timestep `GameLoop`.

## Highlights
- Archetype world + zero-copy component handles powered by Panama segments
- Annotation processor generates descriptors, handles, and registration glue
- Flexible system groups (input, simulation, physics, render) wired through `SystemManager`
- Built-in fixed-step `GameLoop` plus hooks for custom loops or tests
- Modules: `ecs-core`, `ecs-processor`, `ecs-test`, `ecs-benchmark`

## Getting Started
Add the dependency (version stays fluid, use the latest `0.1.+` tag or a SNAPSHOT as needed):

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

## Quick Example
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

Need more? Check `docs/USAGE.md` for deeper walkthroughs (component metadata, builder flags, GameLoop control, publishing tips).


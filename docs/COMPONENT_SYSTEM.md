# Component System (Panama + Annotations)

This document is a publish-ready rewrite of the internal `COMPONENT_SYSTEM.md`, describing how components are modeled, laid out in memory, and managed via the `ComponentManager` and related types.

## 1. Overview

The component system is built around three ideas:

1. A small `Component` marker interface implemented by all components.
2. An annotation-based layout description that drives code and metadata generation.
3. A `ComponentManager` that turns those descriptions into concrete memory layouts and type-safe handles backed by the Java Panama Foreign Memory API.

The goal is to give you low-level control over how data is laid out in memory without sacrificing type safety or ergonomics.

## 2. Component Interface and Annotations

All components implement a simple interface:

```java
public interface Component { }
```

Memory layout is then described with annotations on fields and classes.

### 2.1 `@Component.Field`
Marks a field as part of the component layout and allows you to customize how it is stored:

- `size` – size in bytes (usually inferred from the Java type).
- `offset` – explicit byte offset within the component (for manual layouts).
- `alignment` – alignment requirement in bytes (0 = natural alignment).

Example:
```java
@Component.Field(alignment = 4)
public float x;
```

### 2.2 `@Component.Layout`
Defines the layout strategy for the whole component:

- `SEQUENTIAL` – pack fields one after another without extra padding.
- `PADDING` – insert padding to satisfy alignment requirements.
- `EXPLICIT` – respect explicit `offset` values only; fields go exactly where you say.
- `size` – optional override for the total component size.

Example variants:

```java
// Sequential layout
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class PositionComponent implements Component {
    @Component.Field public float x;
    @Component.Field public float y;
}

// Padded layout
@Component.Layout(Component.LayoutType.PADDING)
public class TransformComponent implements Component {
    @Component.Field(alignment = 4) public float x;
    @Component.Field(alignment = 4) public float y;
    @Component.Field(alignment = 4) public float z;
    @Component.Field(alignment = 4) public float rotation;
}

// Explicit layout
@Component.Layout(value = Component.LayoutType.EXPLICIT, size = 32)
public class HealthComponent implements Component {
    @Component.Field(offset = 0)  public int currentHealth;
    @Component.Field(offset = 4)  public int maxHealth;
    @Component.Field(offset = 8)  public float regenerationRate;
    @Component.Field(offset = 12) public boolean isDead;
}
```

## 3. Core Types

### 3.1 `ComponentManager`
Central registry and metadata engine for components. It is responsible for:

- **Registration** – `registerComponent(Class<?>)` analyzes annotations and builds a `ComponentDescriptor`.
- **Allocation** – `allocate(Class<?>, Arena)` allocates foreign memory for a single component instance using Panama.
- **Handle creation** – `createHandle(Class<?>, MemorySegment)` returns a type-safe accessor bound to a specific memory segment.

### 3.2 `ComponentDescriptor`
Holds the metadata derived from annotations:

- Total component size in bytes.
- Field list with offsets, sizes, and Java types.
- Layout strategy (SEQUENTIAL, PADDING, EXPLICIT).
- Any alignment or management flags needed by the runtime.

Descriptors are used by both the `ComponentManager` and the archetype system to compute chunk layouts and strides.

### 3.3 `ComponentHandle`
A strongly typed view over a `MemorySegment` containing one component instance. It hides Panama details behind field-level getters and setters:

- `getFloat(String fieldName)`, `setFloat(String fieldName, float value)`
- `getInt(String fieldName)`, `setInt(String fieldName, int value)`
- Similar methods for `byte`, `short`, `long`, `double`, `boolean`, `char`.

Generated or hand-written handles can also expose field-specific methods for even better performance (e.g., `getX()`, `setX(float)`), but the generic API is always available.

## 4. Basic Usage

```java
// 1. Create the manager
ComponentManager manager = new ComponentManager();

// 2. Register your components
manager.registerComponent(PositionComponent.class);
manager.registerComponent(VelocityComponent.class);

// 3. Inspect the descriptor
ComponentDescriptor desc = manager.getDescriptor(PositionComponent.class);
System.out.println(desc); // Prints layout information (size, offsets, etc.)

// 4. Allocate memory and create a handle
try (Arena arena = Arena.ofConfined()) {
    MemorySegment segment = manager.allocate(PositionComponent.class, arena);
    ComponentHandle handle = manager.createHandle(PositionComponent.class, segment);

    // 5. Read/write fields via the handle
    handle.setFloat("x", 10.5f);
    handle.setFloat("y", 20.3f);

    float x = handle.getFloat("x");
    float y = handle.getFloat("y");

    System.out.println("Position: " + x + ", " + y);
}
```

## 5. Benefits

1. **Type-safe** – field access is validated at compile time and backed by strongly typed handles.
2. **Flexible layout** – choose SEQUENTIAL/PADDING/EXPLICIT per component.
3. **Memory efficient** – pack structures tightly or align them for SIMD/FFI requirements.
4. **Panama-powered** – uses the Foreign Memory API for off-heap performance.
5. **Reflection-driven** – annotations are processed once; runtime code uses descriptors.
6. **Extensible** – easy to add new component types without changing the core.

## 6. Integration with ArchetypeWorld

The component system plugs directly into the archetype-based world:

1. `ComponentManager` is the single source of truth for component types and descriptors.
2. `ArchetypeWorld` uses descriptors to determine how much memory each component needs and how to store it in chunks.
3. Component handles or generated accessors operate on the memory segments attached to entities inside archetype chunks.
4. Component classes map to internal component type ids, which appear in `ComponentMask`s that define each archetype.

This separation lets you:
- Design components declaratively with annotations.
- Keep layout metadata in one place (`ComponentDescriptor`).
- Reuse the same component definitions across demos, tests, and different worlds.

## 7. Running the Demo

If you want to see the system in action, run the provided demo:

```bash
./gradlew run --args="com.ethnicthv.ecs.demo.ComponentManagerDemo"
```

(Use the Windows variant `gradlew.bat` if needed.)

---

For deeper architectural notes (how this feeds into archetype chunks, managed vs unmanaged components, and shared component stores), see `docs/ARCHITECTURE.md` and `docs/ADVANCED_GUIDE.md`.


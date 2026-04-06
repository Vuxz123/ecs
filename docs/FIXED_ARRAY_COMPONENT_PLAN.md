# Fixed Array Component Plan

## Context

Current unmanaged components support:
- primitive scalar fields only
- nested unmanaged components that can be flattened by the processor

Current unmanaged components do **not** support:
- `int[]`
- `float[]`
- fixed inline buffers such as `neighbors[32]`

That limitation blocks DOTS-style patterns where hot data should live directly in chunk storage, for example:
- boid neighbor buffers
- small contact manifolds
- inventory slots
- fixed-size history windows

Today those use cases require:
- managed objects with Java arrays, which are not chunk-linear
- or external side buffers, which break the “all hot data lives in ECS storage” goal

## Goal

Add **fixed-size inline array fields** for unmanaged components so the following style becomes possible:

```java
@Component.Unmanaged
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public final class NeighborBuffer implements Component {
    @Component.Field(length = 32)
    public int neighbors;

    @Component.Field
    public int count;
}
```

The intended semantics are:
- `neighbors` is stored inline in the component blob
- total storage is `32 * sizeof(int)`
- generated handle exposes indexed access
- chunk layout remains contiguous and SoA-friendly

## Non-Goals

This plan does not try to add:
- runtime-resizable dynamic buffers
- managed array fields stored off-heap automatically
- nested arrays of composite components in phase 1
- query syntax for “array element filters”

Phase 1 is strictly about **fixed-size primitive arrays inline in unmanaged components**.

## Why This Matters

This unlocks engine-level data layouts for workloads that currently need side caches:
- boid fixed neighbor buffers
- broadphase overlap candidates
- fixed event queues per entity
- steering/contact/path samples

It also keeps data linear in chunk storage, unlike managed arrays.

## Proposed Authoring API

### Option A: extend `@Component.Field`

Recommended first step:

```java
@Component.Field(length = 32)
public int neighbors;
```

Rules:
- `length` default is `1`
- `length > 1` means “fixed inline array”
- only primitive field types are allowed when `length > 1`
- nested component flattening and array mode are mutually exclusive in phase 1

### Rejected for Phase 1

```java
@Component.FixedArray(type = int.class, length = 32)
```

This is more verbose and duplicates type information already present in the field declaration.

## Target Runtime Semantics

For:

```java
@Component.Field(length = 32)
public int neighbors;
```

The descriptor should model:
- logical field name: `neighbors`
- element type: `INT`
- element count: `32`
- element size: `4`
- total byte size: `128`
- offset: aligned base offset of the array block

Generated handle should expose:

```java
public int getNeighbors(int index)
public void setNeighbors(int index, int value)
public int lengthOfNeighbors()
```

Optional convenience API:

```java
public long offsetOfNeighbors()
```

Phase 1 should **not** emit:
- scalar `getNeighbors()` without index
- array object allocations

## Required Core Changes

### 1. `Component.Field` annotation

Add:
- `int length() default 1;`

Validation:
- `length >= 1`
- `length > 1` only valid for primitive field types in phase 1

### 2. `ComponentDescriptor.FieldDescriptor`

Current descriptor is scalar-oriented. It needs array metadata:

Suggested shape:

```java
public record FieldDescriptor(
    String name,
    FieldType type,
    long offset,
    long size,
    int alignment,
    int elementCount
) {}
```

Semantics:
- `size` remains total byte size of the field storage
- `elementCount == 1` means scalar
- `elementCount > 1` means fixed inline array

Helper methods to add:
- `boolean isArray()`
- `long elementSize()`

### 3. `ComponentHandle`

Current handle supports scalar getters/setters by field index.

Need indexed APIs:

```java
public int getInt(int fieldIndex, int elementIndex)
public void setInt(int fieldIndex, int elementIndex, int value)
public float getFloat(int fieldIndex, int elementIndex)
public void setFloat(int fieldIndex, int elementIndex, float value)
...
```

Validation:
- field type must match
- field must be array-capable or scalar with `elementIndex == 0`
- bounds-check `0 <= elementIndex < elementCount`

Phase 1 can keep scalar APIs unchanged for `elementCount == 1`.

### 4. `ComponentManager`

Reflection-based registration path must:
- read `length`
- compute total field size as `primitiveSize * length`
- preserve correct alignment rules

Generated-descriptor registration path must mirror the same semantics.

### 5. Chunk Storage

No architectural change is required in `ArchetypeChunk`.

Why:
- chunks already store each unmanaged component as a contiguous blob per entity slot
- fixed array fields just increase component element size

This is the main reason this feature is attractive: it is mostly a descriptor/codegen/handle problem, not a chunk model rewrite.

## Required Processor Changes

### 1. `ComponentProcessor`

Current processor only recognizes scalar primitive fields and composite flattening.

Need:
- parse `length`
- if primitive + `length > 1`, emit a single field descriptor with:
  - original name
  - base offset
  - total byte size
  - elementCount
- do not flatten it into `neighbors_0`, `neighbors_1`, etc.

Reason:
- flat expansion bloats descriptor size and generated API
- indexed handle access is the intended usage

### 2. Generated Meta

Generated descriptor must include `elementCount`.

### 3. Generated Handle

For array field `neighbors`:
- emit `getNeighbors(int index)`
- emit `setNeighbors(int index, int value)`
- emit `neighborsLength()`

For scalar field `count`:
- keep existing `getCount()/setCount(...)`

## Layout Rules

For a primitive array field:
- base offset must be aligned to the primitive’s natural alignment, unless overridden
- total field size is `elementSize * length`
- explicit layout mode may still override offset

Example:

```java
@Component.Field(length = 32)
public int neighbors;

@Component.Field
public int count;
```

Expected:
- `neighbors.offset` aligned to 4
- `neighbors.size == 128`
- `count.offset == neighbors.offset + 128` in sequential layout

## Query and System Impact

Queries do not need a new execution model.

Existing generated query flow should continue to work because:
- systems still receive a handle per component
- the new behavior is only extra indexed access on that handle

Example target usage:

```java
for (int i = 0; i < neighborBuffer.neighborsLength(); i++) {
    int neighbor = neighborBuffer.getNeighbors(i);
}
```

## Boid Demo Target Refactor

Once core support exists, boid demo can move from external fixed-neighbor storage to ECS-owned storage:

```java
Position3
Velocity3
Acceleration3
CellKey
NeighborBuffer
```

Pipeline:
- `SpatialHashSystem`: capture/order spatial snapshot
- `BuildNeighborSystem`: fill `NeighborBuffer` inline on entities
- `SteeringSystem`: read `NeighborBuffer` and do pure compute

This still keeps `SpatialHashGrid` for neighborhood discovery, but the final hot per-boid neighbor list becomes chunk-owned data.

## Validation Rules

The engine should reject:
- `@Component.Field(length = 0)`
- `@Component.Field(length = -1)`
- `@Component.Field(length = 8) public Position3 child;`
- `@Component.Field(length = 4) public String bad;`

Clear diagnostics matter here because processor errors will be the main user feedback loop.

## Test Matrix

### Descriptor Tests

- scalar field keeps `elementCount == 1`
- fixed int array reports correct total size and count
- mixed scalar + array layout offsets are correct

### Handle Tests

- indexed get/set round-trip for `int`, `float`, `byte`
- bounds checks throw correctly
- scalar fields still work with existing scalar API

### Processor / Codegen Tests

- generated handle contains indexed accessors
- generated descriptor includes correct array metadata
- invalid authoring patterns fail compilation cleanly

### Archetype / Query Tests

- array-backed component can be added and read through generated query
- chunk migration preserves array contents
- ECB structural moves preserve array data

### Demo-Level Tests

- boid neighbor buffer can be filled in `BuildNeighborSystem`
- steering reads inline ECS neighbor buffer correctly

## Phased Implementation

### Phase 1. Descriptor and Annotation Support

Deliverables:
- `length` in `@Component.Field`
- `ComponentDescriptor.FieldDescriptor.elementCount`
- registration path computes correct size/alignment

Acceptance:
- descriptor unit tests pass

### Phase 2. Generated Handle Indexed Access

Deliverables:
- indexed get/set APIs for primitive arrays
- generated per-component handle methods for array fields

Acceptance:
- generated code compiles
- indexed round-trip tests pass

### Phase 3. Validation and Diagnostics

Deliverables:
- processor validation for invalid array declarations
- clear compiler errors

Acceptance:
- negative compile tests pass

### Phase 4. Boid Demo Integration

Deliverables:
- add `NeighborBuffer` component in boid demo
- move neighbor list ownership from `SpatialHashGrid` external arrays into ECS component storage
- keep `SpatialHashGrid` only for search/build phase

Acceptance:
- demo behavior parity within tolerance
- benchmark numbers are not materially worse than external-buffer path

### Phase 5. Follow-up Optimizations

Possible additions:
- bulk slice view APIs
- array memset helpers
- SIMD-friendly indexed access helpers
- optional support for fixed arrays of flattened composite types

## Open Questions

1. Should scalar fields allow indexed access with `index == 0` for API uniformity?
2. Should generated handle method names be `getNeighbors(i)` or `neighbors(i)`?
3. Do we want a lightweight “view” object for arrays, or only indexed accessors in phase 1?
4. Should fixed arrays be allowed for shared unmanaged components in phase 1, or instance-only first?

## Recommendation

Implement this as:
- primitive fixed arrays only
- inline in unmanaged component storage
- indexed accessors only
- boid demo migration only after core support is stable

That gives the biggest performance win with the smallest surface-area increase and keeps the feature aligned with the existing archetype/chunk design.

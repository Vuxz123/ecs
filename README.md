# High-Performance ECS (Entity-Component-System) in Java

A modern, cache-friendly Entity-Component-System implementation using **Java 21+ Panama API** (MemorySegment) and **Vector API** for SIMD acceleration.

## 🎉 **NEW: Major Improvements!**

### ✅ **True Structure of Arrays (SoA) Layout**
Components now use separate contiguous arrays for each field:
- **Before**: `[x0,y0, x1,y1, x2,y2...]` (interleaved)
- **After**: `[x0,x1,x2...]` + `[y0,y1,y2...]` (true SoA)

**Benefits:** Better cache locality, superior SIMD performance, CPU prefetcher friendly

### ✅ **Query System API**
Clean, fluent interface for component queries:
```java
// Old way - verbose
for (int i = 0; i < positionEntities.size(); i++) {
    int entityId = positionEntities.getEntity(i);
    // ... complex logic
}

// NEW way - clean & simple!
world.query().withPositionAndVelocity().forEach((id, pIdx, vIdx, pos, vel) -> {
    pos.setX(pIdx, pos.getX(pIdx) + vel.getVX(vIdx) * deltaTime);
});
```

See [IMPROVEMENTS.md](IMPROVEMENTS.md) for detailed changelog.

## 🚀 Key Features

### ✅ **Contiguous Memory Layout**
- Components stored in **off-heap memory** using `MemorySegment` (Project Panama)
- **Structure of Arrays (SoA)** layout for optimal cache locality
- Zero GC overhead for component data

### ✅ **Cache-Friendly Architecture**
- Dense component packing ensures sequential memory access
- Minimizes cache misses during iteration
- Linear traversal patterns for CPU prefetcher efficiency

### ✅ **SIMD Vectorization**
- Vector API support for parallel computation
- Processes multiple entities per iteration using AVX2/SSE instructions
- Significant performance gains for large entity counts

### ✅ **Efficient Entity-Component Mapping**
- **Sparse Set** implementation for O(1) operations:
  - O(1) insertion
  - O(1) deletion (swap-and-pop)
  - O(1) lookup
  - Dense iteration for systems

### ✅ **Modern Java Stack**
- Java 21+ with preview features
- Panama Foreign Function & Memory API
- Incubator Vector API
- Safe memory management with `Arena`

## 📋 Requirements

- **JDK 21+** (with preview features enabled)
- Gradle 8.x

## 🏗️ Architecture

### Component Storage

Each component type is stored in contiguous memory:

```
PositionComponent (SoA layout):
[x0, y0, x1, y1, x2, y2, ...] ← All in one MemorySegment
```

### Sparse Set Mapping

```
Entity ID → Sparse Array → Dense Index → Component Data
    5     →     [5] = 2   →    [2]     → pos[4], pos[5]
```

### Systems

1. **MovementSystem** - Scalar implementation with cache-friendly iteration
2. **VectorizedMovementSystem** - SIMD-optimized using Vector API

## 🎯 Usage

### Basic Example (NEW Query API)

```java
try (World world = new World(10_000)) {
    // Create entity
    int entity = world.createEntity();
    world.addPosition(entity, 10.0f, 20.0f);
    world.addVelocity(entity, 1.0f, 0.5f);
    
    // Use Query API for clean iteration
    world.query().withPositionAndVelocity().forEach((id, pIdx, vIdx, pos, vel) -> {
        // Update logic here
    });
    
    // Count entities
    int count = world.query().withPosition().count();
}
```

### Running the Demos

```bash
# Original demo
./gradlew run

# NEW: Improved demo with Query API
./gradlew runImproved

# Performance benchmark
./gradlew runBenchmark

# Tests
./gradlew test
```

## 📊 Performance Characteristics

### Memory Layout Benefits (NEW)
- **True SoA**: Separate X and Y arrays for maximum cache efficiency
- **SIMD-friendly**: Contiguous floats enable vectorization
- **Zero striding**: Direct sequential access
- **Prefetcher optimal**: Predictable memory patterns

### Sparse Set Benefits
- **O(1) all operations**: Add, remove, check existence
- **Dense packing**: No gaps in iteration
- **Memory efficient**: Only stores active entities
- **Cache coherent**: Linear iteration over dense array

### SIMD Benefits
- **Parallel processing**: 4-8 entities per iteration (depending on CPU)
- **Hardware acceleration**: Uses AVX2/SSE vector instructions
- **Throughput increase**: 2-4x speedup for large datasets

## 🔬 Implementation Details

### Component Storage (PositionComponent)

```java
public class PositionComponent {
    private final MemorySegment segment; // Contiguous off-heap memory
    
    public void set(int index, float x, float y) {
        long base = (long) index * 2;
        segment.setAtIndex(JAVA_FLOAT, base, x);
        segment.setAtIndex(JAVA_FLOAT, base + 1, y);
    }
}
```

### Sparse Set

```java
public class SparseSet {
    private final int[] sparse;  // entity → dense index
    private final int[] dense;   // dense index → entity
    
    public int add(int entityId) {
        int denseIndex = size;
        sparse[entityId] = denseIndex;
        dense[denseIndex] = entityId;
        size++;
        return denseIndex;
    }
}
```

### Vectorized System

```java
FloatVector xVec = FloatVector.fromArray(SPECIES, xValues, 0);
FloatVector vxVec = FloatVector.fromArray(SPECIES, vxValues, 0);
FloatVector newX = xVec.add(vxVec.mul(deltaTimeVec));
newX.intoArray(xValues, 0);
```

## 📈 Expected Performance

Based on modern ECS benchmarks:

| Entities | Scalar | Vectorized | Speedup |
|----------|--------|------------|---------|
| 1,000    | ~5µs   | ~3µs       | 1.7x    |
| 10,000   | ~50µs  | ~25µs      | 2.0x    |
| 100,000  | ~500µs | ~200µs     | 2.5x    |

*Note: Actual performance depends on CPU, cache size, and SIMD support*

## 🧩 Project Structure

```
src/main/java/com/ethnicthv/ecs/
├── ComponentStorage.java          # Base interface for component storages
├── PositionComponent.java         # Position component (x, y) with MemorySegment
├── VelocityComponent.java         # Velocity component (vx, vy)
├── SparseSet.java                 # O(1) entity-component mapping
├── World.java                     # ECS world manager
├── MovementSystem.java            # Scalar movement system
├── VectorizedMovementSystem.java  # SIMD-optimized movement system
├── ECSDemo.java                   # Demo application
└── PerformanceBenchmark.java      # Performance comparison
```

## 🔧 Extending the ECS

### Adding New Components

```java
public class HealthComponent implements ComponentStorage {
    private final MemorySegment healthSegment;
    private final MemorySegment maxHealthSegment;
    
    // True SoA: separate arrays for each field
    public HealthComponent(int capacity, Arena arena) {
        this.healthSegment = arena.allocateArray(JAVA_FLOAT, capacity);
        this.maxHealthSegment = arena.allocateArray(JAVA_FLOAT, capacity);
    }
}
```

### Creating Custom Queries

```java
public class HealthQuery {
    public void forEach(HealthCallback callback) {
        // Iterate over entities with health component
    }
}
```

## 🚀 Next Steps

See [IMPROVEMENTS.md](IMPROVEMENTS.md) for potential future enhancements:
- Archetype-based storage
- Parallel query execution
- Component pooling
- Event system
- Query result caching

---

**Built with Java 21, Panama API, Vector API, and True SoA Layout** 🚀
package com.ethnicthv.ecs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the ECS implementation
 */
class ECSTest {
    
    private World world;
    
    @BeforeEach
    void setUp() {
        world = new World(1000);
    }
    
    @AfterEach
    void tearDown() {
        world.close();
    }
    
    @Test
    void testEntityCreation() {
        int entity1 = world.createEntity();
        int entity2 = world.createEntity();
        
        assertEquals(0, entity1);
        assertEquals(1, entity2);
    }
    
    @Test
    void testAddPosition() {
        int entity = world.createEntity();
        world.addPosition(entity, 10.0f, 20.0f);
        
        assertTrue(world.hasPosition(entity));
        
        int index = world.getPositionEntities().getDenseIndex(entity);
        assertEquals(10.0f, world.getPositions().getX(index), 0.001f);
        assertEquals(20.0f, world.getPositions().getY(index), 0.001f);
    }
    
    @Test
    void testAddVelocity() {
        int entity = world.createEntity();
        world.addVelocity(entity, 5.0f, 3.0f);
        
        assertTrue(world.hasVelocity(entity));
        
        int index = world.getVelocityEntities().getDenseIndex(entity);
        assertEquals(5.0f, world.getVelocities().getVX(index), 0.001f);
        assertEquals(3.0f, world.getVelocities().getVY(index), 0.001f);
    }
    
    @Test
    void testMovementSystem() {
        int entity = world.createEntity();
        world.addPosition(entity, 0.0f, 0.0f);
        world.addVelocity(entity, 10.0f, 5.0f);
        
        MovementSystem system = new MovementSystem();
        system.update(world, 1.0f);
        
        int posIndex = world.getPositionEntities().getDenseIndex(entity);
        assertEquals(10.0f, world.getPositions().getX(posIndex), 0.001f);
        assertEquals(5.0f, world.getPositions().getY(posIndex), 0.001f);
    }
    
    @Test
    void testVectorizedMovementSystem() {
        int entity = world.createEntity();
        world.addPosition(entity, 0.0f, 0.0f);
        world.addVelocity(entity, 10.0f, 5.0f);
        
        VectorizedMovementSystem system = new VectorizedMovementSystem();
        system.update(world, 1.0f);
        
        int posIndex = world.getPositionEntities().getDenseIndex(entity);
        assertEquals(10.0f, world.getPositions().getX(posIndex), 0.001f);
        assertEquals(5.0f, world.getPositions().getY(posIndex), 0.001f);
    }
    
    @Test
    void testMultipleEntities() {
        for (int i = 0; i < 100; i++) {
            int entity = world.createEntity();
            world.addPosition(entity, i, i * 2.0f);
            world.addVelocity(entity, 1.0f, 0.5f);
        }
        
        MovementSystem system = new MovementSystem();
        system.update(world, 2.0f);
        
        // Check a few entities
        int entity0Index = world.getPositionEntities().getDenseIndex(0);
        assertEquals(2.0f, world.getPositions().getX(entity0Index), 0.001f);
        assertEquals(1.0f, world.getPositions().getY(entity0Index), 0.001f);
        
        int entity50Index = world.getPositionEntities().getDenseIndex(50);
        assertEquals(52.0f, world.getPositions().getX(entity50Index), 0.001f);
        assertEquals(101.0f, world.getPositions().getY(entity50Index), 0.001f);
    }
    
    @Test
    void testSparseSet() {
        SparseSet set = new SparseSet(100);
        
        int index1 = set.add(5);
        int index2 = set.add(10);
        int index3 = set.add(20);
        
        assertEquals(0, index1);
        assertEquals(1, index2);
        assertEquals(2, index3);
        
        assertTrue(set.has(5));
        assertTrue(set.has(10));
        assertTrue(set.has(20));
        assertFalse(set.has(15));
        
        assertEquals(3, set.size());
        
        // Test removal
        set.remove(10);
        assertFalse(set.has(10));
        assertEquals(2, set.size());
        
        // Entity 20 should have been swapped to index 1
        assertEquals(20, set.getEntity(1));
    }
    
    @Test
    void testDeleteEntity() {
        int entity = world.createEntity();
        world.addPosition(entity, 10.0f, 20.0f);
        world.addVelocity(entity, 5.0f, 3.0f);
        
        assertTrue(world.hasPosition(entity));
        assertTrue(world.hasVelocity(entity));
        
        world.deleteEntity(entity);
        
        assertFalse(world.hasPosition(entity));
        assertFalse(world.hasVelocity(entity));
    }
    
    @Test
    void testDensePacking() {
        // Create entities with gaps
        int e0 = world.createEntity();
        int e1 = world.createEntity();
        int e2 = world.createEntity();
        
        world.addPosition(e0, 0, 0);
        world.addPosition(e1, 1, 1);
        world.addPosition(e2, 2, 2);
        
        assertEquals(3, world.getPositionEntities().size());
        
        // Delete middle entity
        world.deleteEntity(e1);
        
        assertEquals(2, world.getPositionEntities().size());
        
        // The last entity should have been swapped to maintain density
        assertTrue(world.hasPosition(e0));
        assertFalse(world.hasPosition(e1));
        assertTrue(world.hasPosition(e2));
    }
}

# Hệ thống Component mới với Annotations và Panama Foreign Memory API

## Tổng quan

Hệ thống Component đã được cài đặt lại hoàn toàn với các tính năng sau:

### 1. **Component Interface**
- Tất cả components phải implement interface `Component`
- Sử dụng annotations để định nghĩa layout của components trong memory

### 2. **Annotations**

#### `@Component.Field`
Đánh dấu một field là component field với các tùy chọn:
- `size`: Kích thước tính bằng bytes (mặc định: auto-detect từ type)
- `offset`: Vị trí offset trong memory (mặc định: -1, tự động)
- `alignment`: Yêu cầu alignment (mặc định: 0, natural alignment)

#### `@Component.Layout`
Định nghĩa layout strategy cho component:
- `SEQUENTIAL`: Pack fields liên tiếp không có padding
- `PADDING`: Thêm padding để đảm bảo alignment
- `EXPLICIT`: Sử dụng offset được chỉ định rõ ràng
- `size`: Override tổng kích thước component

### 3. **ComponentManager**
Class chính để quản lý components:
- **Register components**: `registerComponent(Class<?>)` - Phân tích component qua reflection
- **Allocate memory**: `allocate(Class<?>, Arena)` - Cấp phát memory cho component
- **Create handle**: `createHandle(Class<?>, MemorySegment)` - Tạo handle để truy cập dữ liệu

### 4. **ComponentHandle**
Cung cấp type-safe access vào component data:
- `getFloat(String fieldName)`, `setFloat(String fieldName, float value)`
- `getInt(String fieldName)`, `setInt(String fieldName, int value)`
- Và các getter/setter khác cho: byte, short, long, double, boolean, char

### 5. **ComponentDescriptor**
Chứa metadata về memory layout của component:
- Tổng kích thước
- Danh sách các fields với offset và type
- Layout strategy

## Ví dụ Components

### PositionComponent (SEQUENTIAL layout)
```java
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class PositionComponent implements Component {
    @Component.Field
    public float x;
    
    @Component.Field
    public float y;
}
```

### TransformComponent (PADDING layout)
```java
@Component.Layout(Component.LayoutType.PADDING)
public class TransformComponent implements Component {
    @Component.Field(alignment = 4)
    public float x;
    
    @Component.Field(alignment = 4)
    public float y;
    
    @Component.Field(alignment = 4)
    public float z;
    
    @Component.Field(alignment = 4)
    public float rotation;
}
```

### HealthComponent (EXPLICIT layout)
```java
@Component.Layout(value = Component.LayoutType.EXPLICIT, size = 32)
public class HealthComponent implements Component {
    @Component.Field(offset = 0)
    public int currentHealth;
    
    @Component.Field(offset = 4)
    public int maxHealth;
    
    @Component.Field(offset = 8)
    public float regenerationRate;
    
    @Component.Field(offset = 12)
    public boolean isDead;
}
```

## Cách sử dụng

```java
// 1. Tạo ComponentManager
ComponentManager manager = new ComponentManager();

// 2. Register components
manager.registerComponent(PositionComponent.class);
manager.registerComponent(VelocityComponent.class);

// 3. Xem descriptor
ComponentDescriptor desc = manager.getDescriptor(PositionComponent.class);
System.out.println(desc); // In ra layout information

// 4. Allocate memory và tạo handle
try (Arena arena = Arena.ofConfined()) {
    MemorySegment segment = manager.allocate(PositionComponent.class, arena);
    ComponentHandle handle = manager.createHandle(PositionComponent.class, segment);
    
    // 5. Sử dụng handle để get/set data
    handle.setFloat("x", 10.5f);
    handle.setFloat("y", 20.3f);
    
    float x = handle.getFloat("x");
    float y = handle.getFloat("y");
    
    System.out.println("Position: " + x + ", " + y);
}
```

## Lợi ích

1. **Type-safe**: Compiler kiểm tra types tại compile time
2. **Flexible layout**: Hỗ trợ nhiều layout strategies khác nhau
3. **Memory efficient**: Tối ưu hóa memory layout với padding và alignment
4. **Panama API**: Sử dụng Foreign Memory API để truy cập memory hiệu quả
5. **Reflection-based**: Tự động phân tích component structure
6. **Extensible**: Dễ dàng thêm component types mới

## Chạy Demo

```bash
# Chạy ComponentManagerDemo để xem hệ thống hoạt động
gradlew run --args="com.ethnicthv.ecs.demo.ComponentManagerDemo"
```

## Tích hợp với Archetype System

Hệ thống Component mới có thể được tích hợp với ArchetypeWorld bằng cách:

1. Sử dụng ComponentManager để register và quản lý component types
2. Sử dụng ComponentDescriptor để lấy size information cho Archetype
3. Sử dụng ComponentHandle để truy cập component data trong chunks
4. Mapping component classes với component type IDs trong ArchetypeWorld

## Files chính

- `Component.java` - Interface và annotations
- `ComponentManager.java` - Quản lý registration và reflection
- `ComponentHandle.java` - Type-safe accessor
- `ComponentDescriptor.java` - Metadata về layout
- `ComponentManagerDemo.java` - Demo minh họa

## Ghi chú

Các file cũ (World.java, ECSDemo.java, systems cũ) hiện tại không tương thích với hệ thống mới vì đã thay đổi hoàn toàn API. Cần cập nhật hoặc tạo wrapper để tương thích ngược.


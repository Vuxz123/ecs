package com.ethnicthv.ecs.core.components;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ComponentManager - Manages component registration and reflection
 * Uses Panama Foreign Memory API to create efficient memory layouts
 */
public class ComponentManager {
    // Thread-safe registries
    private final ConcurrentHashMap<Class<?>, ComponentDescriptor> descriptors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, Integer> componentTypeIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextTypeId = new AtomicInteger(0);

    // Pool of reusable ComponentHandle instances to avoid allocations when mapping into archetype arrays
    private final ConcurrentLinkedDeque<ComponentHandle> handlePool = new ConcurrentLinkedDeque<>();

    /**
     * Register a component class and analyze its layout using reflection
     */
    public <T> int registerComponent(Class<T> componentClass) {
        // Verify it implements Component interface
        if (!Component.class.isAssignableFrom(componentClass)) {
            throw new IllegalArgumentException(
                componentClass.getName() + " must implement Component interface");
        }

        // Lightweight validation for shared component annotations
        validateSharedAnnotations(componentClass);

        // Assign a stable type id exactly once, even under races
        int typeId = componentTypeIds.computeIfAbsent(componentClass, k -> nextTypeId.getAndIncrement());

        // Prefer generated descriptor if present; else build via reflection
        descriptors.computeIfAbsent(componentClass, cls -> {
            ComponentDescriptor gen = tryLoadGeneratedDescriptor(cls);
            return gen != null ? gen : buildDescriptor(cls);
        });

        return typeId;
    }

    /**
     * Register a component class with a prebuilt descriptor (e.g., from generated meta).
     * Thread-safe and idempotent: type id is assigned once; descriptor installed if absent.
     */
    public <T> int registerComponentWithDescriptor(Class<T> componentClass, ComponentDescriptor descriptor) {
        if (!Component.class.isAssignableFrom(componentClass)) {
            throw new IllegalArgumentException(componentClass.getName() + " must implement Component interface");
        }
        // Validate shared annotations even if descriptor is provided
        validateSharedAnnotations(componentClass);

        int typeId = componentTypeIds.computeIfAbsent(componentClass, k -> nextTypeId.getAndIncrement());
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null for " + componentClass.getName());
        }
        descriptors.putIfAbsent(componentClass, descriptor);
        return typeId;
    }

    /**
     * Get component descriptor
     */
    public ComponentDescriptor getDescriptor(Class<?> componentClass) {
        return descriptors.get(componentClass);
    }

    /**
     * Get component type ID
     */
    public Integer getTypeId(Class<?> componentClass) {
        return componentTypeIds.get(componentClass);
    }

    /**
     * Get all registered component classes
     */
    public Set<Class<?>> getRegisteredComponents() {
        return Collections.unmodifiableSet(componentTypeIds.keySet());
    }

    /**
     * Acquire a reusable ComponentHandle from the pool (or create a new one).
     */
    public ComponentHandle acquireHandle() {
        ComponentHandle h = handlePool.pollFirst();
        if (h == null) {
            h = new ComponentHandle();
        }
        return h;
    }

    /**
     * Release a handle back to the pool. The handle is cleared before pooling.
     */
    public void releaseHandle(ComponentHandle handle) {
        if (handle == null) return;
        handle.clear();
        handlePool.offerFirst(handle);
    }

    /**
     * Auto-closeable wrapper for a pooled ComponentHandle. Calling close() returns the handle to the pool.
     */
    public final class BoundHandle implements AutoCloseable {
        private final ComponentHandle handle;

        private BoundHandle(ComponentHandle handle) {
            this.handle = handle;
        }

        public ComponentHandle handle() {
            return handle;
        }

        @Override
        public void close() {
            releaseHandle(handle);
        }
    }

    /**
     * Acquire a BoundHandle (AutoCloseable) bound to the provided segment. Use try-with-resources to auto-release.
     */
    public BoundHandle acquireBoundHandle(Class<?> componentClass, MemorySegment segment) {
        ComponentHandle h = createHandle(componentClass, segment);
        return new BoundHandle(h);
    }

    public BoundHandle acquireBoundHandleFromArrayElement(Class<?> componentClass, MemorySegment arraySegment, long elementIndex) {
        ComponentHandle h = createHandleFromArrayElement(componentClass, arraySegment, elementIndex);
        return new BoundHandle(h);
    }

    public BoundHandle acquireBoundHandleFromArrayElement(Class<?> componentClass, MemorySegment arraySegment, long elementIndex, long elementSize) {
        ComponentHandle h = createHandleFromArrayElement(componentClass, arraySegment, elementIndex, elementSize);
        return new BoundHandle(h);
    }

    public BoundHandle acquireBoundHandleFromSegmentOffset(Class<?> componentClass, MemorySegment baseSegment, long offset, long elementSize) {
        ComponentHandle h = createHandleFromSegmentOffset(componentClass, baseSegment, offset, elementSize);
        return new BoundHandle(h);
    }

    /**
     * Allocate memory for a component instance
     */
    public MemorySegment allocate(Class<?> componentClass, Arena arena) {
        ComponentDescriptor descriptor = descriptors.get(componentClass);
        if (descriptor == null) {
            throw new IllegalArgumentException("Component " + componentClass + " not registered");
        }
        return arena.allocate(descriptor.getTotalSize(), 8); // 8-byte aligned
    }

    /**
     * Create a handle for accessing component data (pooled)
     */
    public ComponentHandle createHandle(Class<?> componentClass, MemorySegment segment) {
        ComponentDescriptor descriptor = descriptors.get(componentClass);
        if (descriptor == null) {
            throw new IllegalArgumentException("Component " + componentClass + " not registered");
        }
        ComponentHandle h = acquireHandle();
        h.reset(segment, descriptor);
        return h;
    }

    /**
     * Create a ComponentHandle that points to an element inside a larger component array MemorySegment.
     * This is useful when components are stored as contiguous arrays (ArchetypeChunk.componentArrays).
     * The method uses the registered ComponentDescriptor total size to compute the element slice.
     *
     * This variant requires that the component descriptor size is <= per-element size (stride).
     * The returned handle is from the pool; caller should call `releaseHandle(handle)` when done.
     *
     * @param componentClass the component class
     * @param arraySegment the larger MemorySegment containing N elements packed consecutively
     * @param elementIndex the index of the element inside the array (0-based)
     * @return ComponentHandle pointing to the element (slice) without copying
     */
    public ComponentHandle createHandleFromArrayElement(Class<?> componentClass, MemorySegment arraySegment, long elementIndex) {
        ComponentDescriptor descriptor = descriptors.get(componentClass);
        if (descriptor == null) {
            throw new IllegalArgumentException("Component " + componentClass + " not registered");
        }
        long elemSize = descriptor.getTotalSize();
        long arraySize = arraySegment.byteSize();
        long required = (elementIndex + 1) * elemSize;
        if (arraySize < required) {
            throw new IllegalArgumentException("Array segment too small: arraySize=" + arraySize + ", required=" + required);
        }
        MemorySegment elementSlice = arraySegment.asSlice(elemSize * elementIndex, elemSize);
        ComponentHandle h = acquireHandle();
        h.reset(elementSlice, descriptor);
        return h;
    }

    /**
     * Create a ComponentHandle for an element when the per-element size is known/explicit.
     * This variant is helpful if the array stores elements with a stride that differs from registered descriptor size.
     * The method validates that the provided elementSize (stride) is at least as large as the descriptor size.
     * The returned handle is from the pool; caller should call `releaseHandle(handle)` when done.
     *
     * @param componentClass the component class (for descriptor / layout info)
     * @param arraySegment the larger MemorySegment containing elements
     * @param elementIndex zero-based index inside the array
     * @param elementSize size in bytes of each element inside the array (stride)
     * @return ComponentHandle pointing to the element
     */
    public ComponentHandle createHandleFromArrayElement(Class<?> componentClass, MemorySegment arraySegment, long elementIndex, long elementSize) {
        ComponentDescriptor descriptor = descriptors.get(componentClass);
        if (descriptor == null) {
            throw new IllegalArgumentException("Component " + componentClass + " not registered");
        }
        long arraySize = arraySegment.byteSize();
        long required = (elementIndex + 1) * elementSize;
        if (arraySize < required) {
            throw new IllegalArgumentException("Array segment too small: arraySize=" + arraySize + ", required=" + required);
        }
        long descriptorSize = descriptor.getTotalSize();
        if (elementSize < descriptorSize) {
            throw new IllegalArgumentException("Provided elementSize (" + elementSize + ") is smaller than component descriptor size (" + descriptorSize + ")");
        }
        MemorySegment elementSlice = arraySegment.asSlice(elementSize * elementIndex, elementSize);
        ComponentHandle h = acquireHandle();
        h.reset(elementSlice, descriptor);
        return h;
    }

    /**
     * Create a ComponentHandle for a specific offset inside a MemorySegment.
     * Useful when Archetype/Chunk provides a slice or when you want to map a handle onto
     * an existing segment with a specific offset and stride.
     * The returned handle is pooled; call `releaseHandle` after use.
     *
     * @param componentClass component class for descriptor lookup
     * @param baseSegment base MemorySegment
     * @param offset byte offset inside baseSegment where the element starts
     * @param elementSize number of bytes to map for this element (must be >= descriptor size)
     * @return ComponentHandle referring to the slice [offset, offset+elementSize)
     */
    public ComponentHandle createHandleFromSegmentOffset(Class<?> componentClass, MemorySegment baseSegment, long offset, long elementSize) {
        ComponentDescriptor descriptor = descriptors.get(componentClass);
        if (descriptor == null) {
            throw new IllegalArgumentException("Component " + componentClass + " not registered");
        }
        long baseSize = baseSegment.byteSize();
        if (offset < 0 || offset + elementSize > baseSize) {
            throw new IllegalArgumentException("Offset/size out of bounds: offset=" + offset + ", size=" + elementSize + ", baseSize=" + baseSize);
        }
        long descriptorSize = descriptor.getTotalSize();
        if (elementSize < descriptorSize) {
            throw new IllegalArgumentException("elementSize (" + elementSize + ") smaller than descriptor size (" + descriptorSize + ")");
        }
        MemorySegment slice = baseSegment.asSlice(offset, elementSize);
        ComponentHandle h = acquireHandle();
        h.reset(slice, descriptor);
        return h;
    }

    /**
     * Build component descriptor through reflection
     */
    private ComponentDescriptor buildDescriptor(Class<?> componentClass) {
        // Determine kind from annotations
        ComponentDescriptor.ComponentKind kind = getComponentKind(componentClass);

        // Get layout annotation
        Component.Layout layoutAnnotation = componentClass.getAnnotation(Component.Layout.class);
        Component.LayoutType layoutType = layoutAnnotation != null ?
            layoutAnnotation.value() : Component.LayoutType.SEQUENTIAL;

        // Collect all annotated fields
        List<FieldInfo> fieldInfos = new ArrayList<>();
        for (Field field : componentClass.getDeclaredFields()) {
            // Skip static and transient fields
            if (Modifier.isStatic(field.getModifiers()) ||
                Modifier.isTransient(field.getModifiers())) {
                continue;
            }

            Component.Field fieldAnnotation = field.getAnnotation(Component.Field.class);
            if (fieldAnnotation != null) {
                fieldInfos.add(new FieldInfo(field, fieldAnnotation));
            }
        }

        // Sort fields by explicit offset if using EXPLICIT layout
        if (layoutType == Component.LayoutType.EXPLICIT) {
            fieldInfos.sort(Comparator.comparingInt(f -> f.annotation.offset()));
        }

        // Calculate field offsets and total size
        List<ComponentDescriptor.FieldDescriptor> fieldDescriptors = new ArrayList<>();
        long currentOffset = 0;

        for (FieldInfo fieldInfo : fieldInfos) {
            ComponentDescriptor.FieldType fieldType =
                ComponentDescriptor.FieldType.fromJavaType(fieldInfo.field.getType());

            long fieldSize = fieldInfo.annotation.size() > 0 ?
                fieldInfo.annotation.size() : fieldType.getSize();

            int alignment = fieldInfo.annotation.alignment() > 0 ?
                fieldInfo.annotation.alignment() : fieldType.getNaturalAlignment();

            // Calculate offset
            long offset;
            if (layoutType == Component.LayoutType.EXPLICIT && fieldInfo.annotation.offset() >= 0) {
                offset = fieldInfo.annotation.offset();
            } else if (layoutType == Component.LayoutType.PADDING) {
                // Align to field's alignment requirement
                offset = alignUp(currentOffset, alignment);
            } else {
                // SEQUENTIAL - no padding
                offset = currentOffset;
            }

            fieldDescriptors.add(new ComponentDescriptor.FieldDescriptor(
                fieldInfo.field.getName(),
                fieldType,
                offset,
                fieldSize,
                alignment
            ));

            currentOffset = offset + fieldSize;
        }

        // Calculate total size
        long totalSize;
        if (layoutAnnotation != null && layoutAnnotation.size() > 0) {
            totalSize = layoutAnnotation.size();
        } else if (layoutType == Component.LayoutType.PADDING && !fieldDescriptors.isEmpty()) {
            // Align to largest alignment requirement
            int maxAlignment = fieldDescriptors.stream()
                .mapToInt(ComponentDescriptor.FieldDescriptor::alignment)
                .max()
                .orElse(1);
            totalSize = alignUp(currentOffset, maxAlignment);
        } else {
            totalSize = currentOffset;
        }

        return new ComponentDescriptor(componentClass, totalSize, fieldDescriptors, layoutType, kind);
    }

    private static ComponentDescriptor.ComponentKind getComponentKind(Class<?> componentClass) {
        boolean isManaged = componentClass.isAnnotationPresent(Component.Managed.class);
        boolean isShared = componentClass.isAnnotationPresent(Component.Shared.class);
        ComponentDescriptor.ComponentKind kind;
        if (isShared) {
            kind = isManaged ? ComponentDescriptor.ComponentKind.SHARED_MANAGED : ComponentDescriptor.ComponentKind.SHARED_UNMANAGED;
        } else {
            kind = isManaged ? ComponentDescriptor.ComponentKind.INSTANCE_MANAGED : ComponentDescriptor.ComponentKind.INSTANCE_UNMANAGED;
        }
        return kind;
    }

    /**
     * Align offset to specified alignment
     */
    private long alignUp(long offset, int alignment) {
        return ((offset + alignment - 1) / alignment) * alignment;
    }

    /**
     * Helper class to hold field info during reflection
     */
    private static class FieldInfo {
        final Field field;
        final Component.Field annotation;

        FieldInfo(Field field, Component.Field annotation) {
            this.field = field;
            this.annotation = annotation;
        }
    }

    // Try to load a generated meta class <FQN> + "Meta" exposing DESCRIPTOR field or descriptor() method.
    private ComponentDescriptor tryLoadGeneratedDescriptor(Class<?> componentClass) {
        String metaName = componentClass.getName() + "Meta";
        try {
            Class<?> meta = Class.forName(metaName, false, componentClass.getClassLoader());
            try {
                java.lang.reflect.Field f = meta.getField("DESCRIPTOR");
                Object v = f.get(null);
                if (v instanceof ComponentDescriptor cd) return cd;
            } catch (NoSuchFieldException ignored) { }
            try {
                java.lang.reflect.Method m = meta.getMethod("descriptor");
                Object v = m.invoke(null);
                if (v instanceof ComponentDescriptor cd) return cd;
            } catch (NoSuchMethodException ignored) { }
        } catch (Throwable ignored) {
            // not generated/present
        }
        return null;
    }

    /**
     * Lightweight validation for shared component annotations.
     * - @SharedComponent must be a class that overrides equals(Object) and hashCode().
     * - @UnmanagedSharedComponent must be an interface whose declared methods are only getters (0-arg, returns int/long)
     *   or setters (1-arg int/long, returns void).
     */
    private void validateSharedAnnotations(Class<?> cls) {
        boolean hasShared = cls.isAnnotationPresent(Component.Shared.class);
        boolean hasManaged = cls.isAnnotationPresent(Component.Managed.class);

        if (hasShared) {
            if (cls.isInterface()) {
                throw new IllegalArgumentException("@SharedComponent must be placed on a class, not an interface: " + cls.getName());
            }
            // Must override equals(Object) and hashCode() (possibly via a superclass, but not from Object)
            try {
                Method eq = cls.getMethod("equals", Object.class);
                if (eq.getDeclaringClass() == Object.class) {
                    throw new NoSuchMethodException();
                }
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("Class " + cls.getName() + " is annotated @SharedComponent but does not override equals(Object)");
            }
            try {
                Method hc = cls.getMethod("hashCode");
                if (hc.getDeclaringClass() == Object.class) {
                    throw new NoSuchMethodException();
                }
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("Class " + cls.getName() + " is annotated @SharedComponent but does not override hashCode()");
            }
        }

        if (hasShared && hasManaged) {
            if (!cls.isInterface()) {
                throw new IllegalArgumentException("@UnmanagedSharedComponent must be placed on an interface: " + cls.getName());
            }
            Method[] declared = cls.getDeclaredMethods();
            if (declared.length == 0) {
                throw new IllegalArgumentException("@UnmanagedSharedComponent interface must declare at least one getter/setter method: " + cls.getName());
            }
            for (Method m : declared) {
                int paramCount = m.getParameterCount();
                Class<?> ret = m.getReturnType();
                if (paramCount == 0) {
                    // getter: returns int or long (primitive)
                    if (!(ret == int.class || ret == long.class)) {
                        throw new IllegalArgumentException("Getter method '" + m.getName() + "' in @UnmanagedSharedComponent must return int or long: " + cls.getName());
                    }
                } else if (paramCount == 1) {
                    // setter: one int/long param, returns void
                    Class<?> p0 = m.getParameterTypes()[0];
                    if (!((p0 == int.class || p0 == long.class) && ret == void.class)) {
                        throw new IllegalArgumentException("Setter method '" + m.getName() + "' in @UnmanagedSharedComponent must accept (int|long) and return void: " + cls.getName());
                    }
                } else {
                    throw new IllegalArgumentException("Method '" + m.getName() + "' in @UnmanagedSharedComponent must be a getter or setter for a single int/long: " + cls.getName());
                }
            }
        }
    }
}

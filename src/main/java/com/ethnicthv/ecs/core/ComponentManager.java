package com.ethnicthv.ecs.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * ComponentManager - Manages component registration and reflection
 * Uses Panama Foreign Memory API to create efficient memory layouts
 */
public class ComponentManager {
    private final Map<Class<?>, ComponentDescriptor> descriptors = new HashMap<>();
    private final Map<Class<?>, Integer> componentTypeIds = new HashMap<>();
    private int nextTypeId = 0;

    // Pool of reusable ComponentHandle instances to avoid allocations when mapping into archetype arrays
    private final ConcurrentLinkedDeque<ComponentHandle> handlePool = new ConcurrentLinkedDeque<>();

    /**
     * Register a component class and analyze its layout using reflection
     */
    public <T> int registerComponent(Class<T> componentClass) {
        if (componentTypeIds.containsKey(componentClass)) {
            return componentTypeIds.get(componentClass);
        }

        // Verify it implements Component interface
        if (!Component.class.isAssignableFrom(componentClass)) {
            throw new IllegalArgumentException(
                componentClass.getName() + " must implement Component interface");
        }

        // Build descriptor through reflection
        ComponentDescriptor descriptor = buildDescriptor(componentClass);
        descriptors.put(componentClass, descriptor);

        int typeId = nextTypeId++;
        componentTypeIds.put(componentClass, typeId);

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

        return new ComponentDescriptor(componentClass, totalSize, fieldDescriptors, layoutType);
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
}

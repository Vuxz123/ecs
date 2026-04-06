package com.ethnicthv.ecs.core.components;

import com.ethnicthv.ecs.core.api.components.IReadWriteComponentHandle;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Reusable handle to access component data in memory using Panama Foreign Memory API
 * The handle can be reset to point at a different MemorySegment/descriptor so it can be pooled.
 */
public class ComponentHandle implements IReadWriteComponentHandle {
    private MemorySegment segment; // mutable so the handle can be reused
    private ComponentDescriptor descriptor;

    /**
     * No-arg constructor to allow pooling + later reset
     */
    public ComponentHandle() {
        this.segment = null;
        this.descriptor = null;
    }

    /**
     * Construct and bind to a segment immediately
     */
    public ComponentHandle(MemorySegment segment, ComponentDescriptor descriptor) {
        this.segment = segment;
        this.descriptor = descriptor;
    }

    /**
     * Bind this handle to a MemorySegment and descriptor (reusable)
     */
    public void reset(MemorySegment segment, ComponentDescriptor descriptor) {
        this.segment = segment;
        this.descriptor = descriptor;
    }

    /**
     * Unbind / clear the handle to prepare for pooling
     */
    public void clear() {
        this.segment = null;
        this.descriptor = null;
    }

    private void ensureBound() {
        if (segment == null || descriptor == null) {
            throw new IllegalStateException("ComponentHandle is not bound to a segment/descriptor");
        }
    }

    private void ensureIndexInRange(int index) {
        int count = descriptor.fieldCount();
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("Field index out of range: " + index + " (count=" + count + ")");
        }
    }

    private ComponentDescriptor.FieldDescriptor resolveField(int fieldIndex) {
        ensureBound();
        ensureIndexInRange(fieldIndex);
        return descriptor.getField(fieldIndex);
    }

    private void ensureFieldType(ComponentDescriptor.FieldDescriptor field, ComponentDescriptor.FieldType expected) {
        if (field.type() != expected) {
            throw new IllegalArgumentException(
                "Field '" + field.name() + "' has type " + field.type() + ", expected " + expected);
        }
    }

    private void ensureScalarField(ComponentDescriptor.FieldDescriptor field) {
        if (field.isArray()) {
            throw new IllegalArgumentException(
                "Field '" + field.name() + "' is a fixed array; use indexed access instead");
        }
    }

    private long resolveElementOffset(ComponentDescriptor.FieldDescriptor field, int elementIndex) {
        int elementCount = field.elementCount();
        if (elementIndex < 0 || elementIndex >= elementCount) {
            throw new IndexOutOfBoundsException(
                "Element index out of range for field '" + field.name() + "': "
                    + elementIndex + " (count=" + elementCount + ")"
            );
        }
        return field.offset() + (field.elementSize() * elementIndex);
    }

    /**
     * Resolve a field name to its index. Prefer calling once at setup and reusing the index.
     */
    public int resolveFieldIndex(String fieldName) {
        ensureBound();
        int idx = descriptor.getFieldIndex(fieldName);
        if (idx < 0) {
            throw new IllegalArgumentException("Field " + fieldName + " not found");
        }
        return idx;
    }

    // ------------- Index-based hot-path getters -------------
    @Override
    public byte getByte(int fieldIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.BYTE);
        ensureScalarField(f);
        return segment.get(ValueLayout.JAVA_BYTE, f.offset());
    }
    @Override
    public byte getByte(int fieldIndex, int elementIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.BYTE);
        return segment.get(ValueLayout.JAVA_BYTE, resolveElementOffset(f, elementIndex));
    }
    @Override
    public short getShort(int fieldIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.SHORT);
        ensureScalarField(f);
        return segment.get(ValueLayout.JAVA_SHORT, f.offset());
    }
    @Override
    public short getShort(int fieldIndex, int elementIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.SHORT);
        return segment.get(ValueLayout.JAVA_SHORT, resolveElementOffset(f, elementIndex));
    }
    @Override
    public int getInt(int fieldIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.INT);
        ensureScalarField(f);
        return segment.get(ValueLayout.JAVA_INT, f.offset());
    }
    @Override
    public int getInt(int fieldIndex, int elementIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.INT);
        return segment.get(ValueLayout.JAVA_INT, resolveElementOffset(f, elementIndex));
    }
    @Override
    public long getLong(int fieldIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.LONG);
        ensureScalarField(f);
        return segment.get(ValueLayout.JAVA_LONG, f.offset());
    }
    @Override
    public long getLong(int fieldIndex, int elementIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.LONG);
        return segment.get(ValueLayout.JAVA_LONG, resolveElementOffset(f, elementIndex));
    }
    @Override
    public float getFloat(int fieldIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.FLOAT);
        ensureScalarField(f);
        return segment.get(ValueLayout.JAVA_FLOAT, f.offset());
    }
    @Override
    public float getFloat(int fieldIndex, int elementIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.FLOAT);
        return segment.get(ValueLayout.JAVA_FLOAT, resolveElementOffset(f, elementIndex));
    }
    @Override
    public double getDouble(int fieldIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.DOUBLE);
        ensureScalarField(f);
        return segment.get(ValueLayout.JAVA_DOUBLE, f.offset());
    }
    @Override
    public double getDouble(int fieldIndex, int elementIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.DOUBLE);
        return segment.get(ValueLayout.JAVA_DOUBLE, resolveElementOffset(f, elementIndex));
    }
    @Override
    public boolean getBoolean(int fieldIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.BOOLEAN);
        ensureScalarField(f);
        return segment.get(ValueLayout.JAVA_BOOLEAN, f.offset());
    }
    @Override
    public boolean getBoolean(int fieldIndex, int elementIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.BOOLEAN);
        return segment.get(ValueLayout.JAVA_BOOLEAN, resolveElementOffset(f, elementIndex));
    }
    @Override
    public char getChar(int fieldIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.CHAR);
        ensureScalarField(f);
        return segment.get(ValueLayout.JAVA_CHAR, f.offset());
    }
    @Override
    public char getChar(int fieldIndex, int elementIndex) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.CHAR);
        return segment.get(ValueLayout.JAVA_CHAR, resolveElementOffset(f, elementIndex));
    }

    // ------------- Index-based hot-path setters -------------
    @Override
    public void setByte(int fieldIndex, byte value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.BYTE);
        ensureScalarField(f);
        segment.set(ValueLayout.JAVA_BYTE, f.offset(), value);
    }
    @Override
    public void setByte(int fieldIndex, int elementIndex, byte value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.BYTE);
        segment.set(ValueLayout.JAVA_BYTE, resolveElementOffset(f, elementIndex), value);
    }
    @Override
    public void setShort(int fieldIndex, short value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.SHORT);
        ensureScalarField(f);
        segment.set(ValueLayout.JAVA_SHORT, f.offset(), value);
    }
    @Override
    public void setShort(int fieldIndex, int elementIndex, short value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.SHORT);
        segment.set(ValueLayout.JAVA_SHORT, resolveElementOffset(f, elementIndex), value);
    }
    @Override
    public void setInt(int fieldIndex, int value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.INT);
        ensureScalarField(f);
        segment.set(ValueLayout.JAVA_INT, f.offset(), value);
    }
    @Override
    public void setInt(int fieldIndex, int elementIndex, int value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.INT);
        segment.set(ValueLayout.JAVA_INT, resolveElementOffset(f, elementIndex), value);
    }
    @Override
    public void setLong(int fieldIndex, long value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.LONG);
        ensureScalarField(f);
        segment.set(ValueLayout.JAVA_LONG, f.offset(), value);
    }
    @Override
    public void setLong(int fieldIndex, int elementIndex, long value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.LONG);
        segment.set(ValueLayout.JAVA_LONG, resolveElementOffset(f, elementIndex), value);
    }
    @Override
    public void setFloat(int fieldIndex, float value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.FLOAT);
        ensureScalarField(f);
        segment.set(ValueLayout.JAVA_FLOAT, f.offset(), value);
    }
    @Override
    public void setFloat(int fieldIndex, int elementIndex, float value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.FLOAT);
        segment.set(ValueLayout.JAVA_FLOAT, resolveElementOffset(f, elementIndex), value);
    }
    @Override
    public void setDouble(int fieldIndex, double value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.DOUBLE);
        ensureScalarField(f);
        segment.set(ValueLayout.JAVA_DOUBLE, f.offset(), value);
    }
    @Override
    public void setDouble(int fieldIndex, int elementIndex, double value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.DOUBLE);
        segment.set(ValueLayout.JAVA_DOUBLE, resolveElementOffset(f, elementIndex), value);
    }
    @Override
    public void setBoolean(int fieldIndex, boolean value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.BOOLEAN);
        ensureScalarField(f);
        segment.set(ValueLayout.JAVA_BOOLEAN, f.offset(), value);
    }
    @Override
    public void setBoolean(int fieldIndex, int elementIndex, boolean value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.BOOLEAN);
        segment.set(ValueLayout.JAVA_BOOLEAN, resolveElementOffset(f, elementIndex), value);
    }
    @Override
    public void setChar(int fieldIndex, char value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.CHAR);
        ensureScalarField(f);
        segment.set(ValueLayout.JAVA_CHAR, f.offset(), value);
    }
    @Override
    public void setChar(int fieldIndex, int elementIndex, char value) {
        var f = resolveField(fieldIndex);
        ensureFieldType(f, ComponentDescriptor.FieldType.CHAR);
        segment.set(ValueLayout.JAVA_CHAR, resolveElementOffset(f, elementIndex), value);
    }

    // ------------- Legacy name-based API (routes through index resolution) -------------
    /**
     * Get a field value by name. Use only for setup; prefer index-based methods in hot-path.
     */
    @Override
    public Object get(String fieldName) {
        ensureBound();
        int idx = descriptor.getFieldIndex(fieldName);
        if (idx < 0) {
            throw new IllegalArgumentException("Field " + fieldName + " not found");
        }
        return getByIndex(idx);
    }

    @Override
    public Object getByIndex(int idx) {
        var field = descriptor.getField(idx);
        if (field.isArray()) {
            throw new IllegalArgumentException(
                "Field '" + field.name() + "' is a fixed array; use indexed access instead");
        }
        return switch (field.type()) {
            case BYTE -> segment.get(ValueLayout.JAVA_BYTE, field.offset());
            case SHORT -> segment.get(ValueLayout.JAVA_SHORT, field.offset());
            case INT -> segment.get(ValueLayout.JAVA_INT, field.offset());
            case LONG -> segment.get(ValueLayout.JAVA_LONG, field.offset());
            case FLOAT -> segment.get(ValueLayout.JAVA_FLOAT, field.offset());
            case DOUBLE -> segment.get(ValueLayout.JAVA_DOUBLE, field.offset());
            case BOOLEAN -> segment.get(ValueLayout.JAVA_BOOLEAN, field.offset());
            case CHAR -> segment.get(ValueLayout.JAVA_CHAR, field.offset());
        };
    }

    /**
     * Set a field value by name. Use only for setup; prefer index-based methods in hot-path.
     */
    @Override
    public void set(String fieldName, Object value) {
        ensureBound();
        int idx = descriptor.getFieldIndex(fieldName);
        if (idx < 0) {
            throw new IllegalArgumentException("Field " + fieldName + " not found");
        }
        setByIndex(idx, value);
    }

    @Override
    public void setByIndex(int idx, Object value) {
        var field = descriptor.getField(idx);
        if (field.isArray()) {
            throw new IllegalArgumentException(
                "Field '" + field.name() + "' is a fixed array; use indexed access instead");
        }
        switch (field.type()) {
            case BYTE -> segment.set(ValueLayout.JAVA_BYTE, field.offset(), (byte) value);
            case SHORT -> segment.set(ValueLayout.JAVA_SHORT, field.offset(), (short) value);
            case INT -> segment.set(ValueLayout.JAVA_INT, field.offset(), (int) value);
            case LONG -> segment.set(ValueLayout.JAVA_LONG, field.offset(), (long) value);
            case FLOAT -> segment.set(ValueLayout.JAVA_FLOAT, field.offset(), (float) value);
            case DOUBLE -> segment.set(ValueLayout.JAVA_DOUBLE, field.offset(), (double) value);
            case BOOLEAN -> segment.set(ValueLayout.JAVA_BOOLEAN, field.offset(), (boolean) value);
            case CHAR -> segment.set(ValueLayout.JAVA_CHAR, field.offset(), (char) value);
        }
    }

    /** Type-safe getters/setters proxying to generic get/set by name (setup-friendly)
     * Prefer index-based overloads for hot-path.
     */
    @Override
    public byte getByte(String fieldName) { return (byte) get(fieldName); }
    @Override
    public short getShort(String fieldName) { return (short) get(fieldName); }
    @Override
    public int getInt(String fieldName) { return (int) get(fieldName); }
    @Override
    public long getLong(String fieldName) { return (long) get(fieldName); }
    @Override
    public float getFloat(String fieldName) { return (float) get(fieldName); }
    @Override
    public double getDouble(String fieldName) { return (double) get(fieldName); }
    @Override
    public boolean getBoolean(String fieldName) { return (boolean) get(fieldName); }
    @Override
    public char getChar(String fieldName) { return (char) get(fieldName); }

    @Override
    public void setByte(String fieldName, byte value) { set(fieldName, value); }
    @Override
    public void setShort(String fieldName, short value) { set(fieldName, value); }
    @Override
    public void setInt(String fieldName, int value) { set(fieldName, value); }
    @Override
    public void setLong(String fieldName, long value) { set(fieldName, value); }
    @Override
    public void setFloat(String fieldName, float value) { set(fieldName, value); }
    @Override
    public void setDouble(String fieldName, double value) { set(fieldName, value); }
    @Override
    public void setBoolean(String fieldName, boolean value) { set(fieldName, value); }
    @Override
    public void setChar(String fieldName, char value) { set(fieldName, value); }

    @Override
    public MemorySegment getSegment() { return segment; }
    @Override
    public ComponentDescriptor getDescriptor() { return descriptor; }
}

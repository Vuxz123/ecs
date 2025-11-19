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
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        return segment.get(ValueLayout.JAVA_BYTE, f.offset());
    }
    @Override
    public short getShort(int fieldIndex) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        return segment.get(ValueLayout.JAVA_SHORT, f.offset());
    }
    @Override
    public int getInt(int fieldIndex) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        return segment.get(ValueLayout.JAVA_INT, f.offset());
    }
    @Override
    public long getLong(int fieldIndex) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        return segment.get(ValueLayout.JAVA_LONG, f.offset());
    }
    @Override
    public float getFloat(int fieldIndex) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        return segment.get(ValueLayout.JAVA_FLOAT, f.offset());
    }
    @Override
    public double getDouble(int fieldIndex) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        return segment.get(ValueLayout.JAVA_DOUBLE, f.offset());
    }
    @Override
    public boolean getBoolean(int fieldIndex) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        return segment.get(ValueLayout.JAVA_BOOLEAN, f.offset());
    }
    @Override
    public char getChar(int fieldIndex) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        return segment.get(ValueLayout.JAVA_CHAR, f.offset());
    }

    // ------------- Index-based hot-path setters -------------
    @Override
    public void setByte(int fieldIndex, byte value) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        segment.set(ValueLayout.JAVA_BYTE, f.offset(), value);
    }
    @Override
    public void setShort(int fieldIndex, short value) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        segment.set(ValueLayout.JAVA_SHORT, f.offset(), value);
    }
    @Override
    public void setInt(int fieldIndex, int value) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        segment.set(ValueLayout.JAVA_INT, f.offset(), value);
    }
    @Override
    public void setLong(int fieldIndex, long value) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        segment.set(ValueLayout.JAVA_LONG, f.offset(), value);
    }
    @Override
    public void setFloat(int fieldIndex, float value) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        segment.set(ValueLayout.JAVA_FLOAT, f.offset(), value);
    }
    @Override
    public void setDouble(int fieldIndex, double value) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        segment.set(ValueLayout.JAVA_DOUBLE, f.offset(), value);
    }
    @Override
    public void setBoolean(int fieldIndex, boolean value) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        segment.set(ValueLayout.JAVA_BOOLEAN, f.offset(), value);
    }
    @Override
    public void setChar(int fieldIndex, char value) {
        ensureBound(); ensureIndexInRange(fieldIndex);
        var f = descriptor.getField(fieldIndex);
        segment.set(ValueLayout.JAVA_CHAR, f.offset(), value);
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
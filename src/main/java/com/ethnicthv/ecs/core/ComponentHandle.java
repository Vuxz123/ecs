package com.ethnicthv.ecs.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Reusable handle to access component data in memory using Panama Foreign Memory API
 * The handle can be reset to point at a different MemorySegment/descriptor so it can be pooled.
 */
public class ComponentHandle {
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

    /**
     * Get a field value by name
     */
    public Object get(String fieldName) {
        ensureBound();
        ComponentDescriptor.FieldDescriptor field = descriptor.getField(fieldName);
        if (field == null) {
            throw new IllegalArgumentException("Field " + fieldName + " not found");
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
     * Set a field value by name
     */
    public void set(String fieldName, Object value) {
        ensureBound();
        ComponentDescriptor.FieldDescriptor field = descriptor.getField(fieldName);
        if (field == null) {
            throw new IllegalArgumentException("Field " + fieldName + " not found");
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

    /** Type-safe getters/setters proxying to generic get/set */
    public byte getByte(String fieldName) { return (byte) get(fieldName); }
    public short getShort(String fieldName) { return (short) get(fieldName); }
    public int getInt(String fieldName) { return (int) get(fieldName); }
    public long getLong(String fieldName) { return (long) get(fieldName); }
    public float getFloat(String fieldName) { return (float) get(fieldName); }
    public double getDouble(String fieldName) { return (double) get(fieldName); }
    public boolean getBoolean(String fieldName) { return (boolean) get(fieldName); }
    public char getChar(String fieldName) { return (char) get(fieldName); }

    public void setByte(String fieldName, byte value) { set(fieldName, value); }
    public void setShort(String fieldName, short value) { set(fieldName, value); }
    public void setInt(String fieldName, int value) { set(fieldName, value); }
    public void setLong(String fieldName, long value) { set(fieldName, value); }
    public void setFloat(String fieldName, float value) { set(fieldName, value); }
    public void setDouble(String fieldName, double value) { set(fieldName, value); }
    public void setBoolean(String fieldName, boolean value) { set(fieldName, value); }
    public void setChar(String fieldName, char value) { set(fieldName, value); }

    public MemorySegment getSegment() { return segment; }
    public ComponentDescriptor getDescriptor() { return descriptor; }
}
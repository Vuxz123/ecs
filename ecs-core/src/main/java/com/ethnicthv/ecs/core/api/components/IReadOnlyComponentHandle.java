package com.ethnicthv.ecs.core.api.components;

import com.ethnicthv.ecs.core.components.ComponentDescriptor;

import java.lang.foreign.MemorySegment;

public interface IReadOnlyComponentHandle {
    // ------------- Index-based hot-path getters -------------
    byte getByte(int fieldIndex);

    short getShort(int fieldIndex);

    int getInt(int fieldIndex);

    long getLong(int fieldIndex);

    float getFloat(int fieldIndex);

    double getDouble(int fieldIndex);

    boolean getBoolean(int fieldIndex);

    char getChar(int fieldIndex);

    Object get(String fieldName);

    Object getByIndex(int idx);

    byte getByte(String fieldName);

    short getShort(String fieldName);

    int getInt(String fieldName);

    long getLong(String fieldName);

    float getFloat(String fieldName);

    double getDouble(String fieldName);

    boolean getBoolean(String fieldName);

    char getChar(String fieldName);

    MemorySegment getSegment();

    ComponentDescriptor getDescriptor();
}

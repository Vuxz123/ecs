package com.ethnicthv.ecs.core.api.components;

import com.ethnicthv.ecs.core.components.ComponentDescriptor;

import java.lang.foreign.MemorySegment;

public interface IReadOnlyComponentHandle {
    // ------------- Index-based hot-path getters -------------
    byte getByte(int fieldIndex);

    byte getByte(int fieldIndex, int elementIndex);

    short getShort(int fieldIndex);

    short getShort(int fieldIndex, int elementIndex);

    int getInt(int fieldIndex);

    int getInt(int fieldIndex, int elementIndex);

    long getLong(int fieldIndex);

    long getLong(int fieldIndex, int elementIndex);

    float getFloat(int fieldIndex);

    float getFloat(int fieldIndex, int elementIndex);

    double getDouble(int fieldIndex);

    double getDouble(int fieldIndex, int elementIndex);

    boolean getBoolean(int fieldIndex);

    boolean getBoolean(int fieldIndex, int elementIndex);

    char getChar(int fieldIndex);

    char getChar(int fieldIndex, int elementIndex);

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

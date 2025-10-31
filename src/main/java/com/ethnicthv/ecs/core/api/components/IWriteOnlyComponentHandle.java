package com.ethnicthv.ecs.core.api.components;

import com.ethnicthv.ecs.core.components.ComponentDescriptor;

public interface IWriteOnlyComponentHandle {
    // ------------- Index-based hot-path setters -------------
    void setByte(int fieldIndex, byte value);

    void setShort(int fieldIndex, short value);

    void setInt(int fieldIndex, int value);

    void setLong(int fieldIndex, long value);

    void setFloat(int fieldIndex, float value);

    void setDouble(int fieldIndex, double value);

    void setBoolean(int fieldIndex, boolean value);

    void setChar(int fieldIndex, char value);

    void set(String fieldName, Object value);

    void setByIndex(int idx, Object value);

    void setByte(String fieldName, byte value);

    void setShort(String fieldName, short value);

    void setInt(String fieldName, int value);

    void setLong(String fieldName, long value);

    void setFloat(String fieldName, float value);

    void setDouble(String fieldName, double value);

    void setBoolean(String fieldName, boolean value);

    void setChar(String fieldName, char value);

    ComponentDescriptor getDescriptor();
}

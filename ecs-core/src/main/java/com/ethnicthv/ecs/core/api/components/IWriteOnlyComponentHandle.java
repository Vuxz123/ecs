package com.ethnicthv.ecs.core.api.components;

import com.ethnicthv.ecs.core.components.ComponentDescriptor;

public interface IWriteOnlyComponentHandle {
    // ------------- Index-based hot-path setters -------------
    void setByte(int fieldIndex, byte value);

    void setByte(int fieldIndex, int elementIndex, byte value);

    void setShort(int fieldIndex, short value);

    void setShort(int fieldIndex, int elementIndex, short value);

    void setInt(int fieldIndex, int value);

    void setInt(int fieldIndex, int elementIndex, int value);

    void setLong(int fieldIndex, long value);

    void setLong(int fieldIndex, int elementIndex, long value);

    void setFloat(int fieldIndex, float value);

    void setFloat(int fieldIndex, int elementIndex, float value);

    void setDouble(int fieldIndex, double value);

    void setDouble(int fieldIndex, int elementIndex, double value);

    void setBoolean(int fieldIndex, boolean value);

    void setBoolean(int fieldIndex, int elementIndex, boolean value);

    void setChar(int fieldIndex, char value);

    void setChar(int fieldIndex, int elementIndex, char value);

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

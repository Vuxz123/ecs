package com.ethnicthv.ecs.core.archetype;

import java.util.Arrays;

/**
 * Immutable key used to group entities by their shared component values.
 */
public record SharedValueKey(int[] managedSharedIndices, long[] unmanagedSharedValues) {
    public SharedValueKey {
        // defensive copies to maintain immutability
        managedSharedIndices = managedSharedIndices != null ? managedSharedIndices.clone() : null;
        unmanagedSharedValues = unmanagedSharedValues != null ? unmanagedSharedValues.clone() : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SharedValueKey that)) return false;
        return Arrays.equals(this.managedSharedIndices, that.managedSharedIndices)
            && Arrays.equals(this.unmanagedSharedValues, that.unmanagedSharedValues);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(this.managedSharedIndices);
        result = 31 * result + Arrays.hashCode(this.unmanagedSharedValues);
        return result;
    }
}


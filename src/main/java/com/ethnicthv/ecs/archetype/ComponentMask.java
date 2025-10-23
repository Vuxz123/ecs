package com.ethnicthv.ecs.archetype;

import java.util.BitSet;
import java.util.Objects;

/**
 * Represents a unique signature of components that an entity possesses.
 * Used to identify and group entities with the same component composition.
 */
public final class ComponentMask {
    private final BitSet mask;
    private final int hashCode;

    public ComponentMask() {
        this.mask = new BitSet();
        this.hashCode = 0;
    }

    private ComponentMask(BitSet mask) {
        this.mask = (BitSet) mask.clone();
        this.hashCode = mask.hashCode();
    }

    /**
     * Set a component bit in the mask
     */
    public ComponentMask set(int componentId) {
        BitSet newMask = (BitSet) mask.clone();
        newMask.set(componentId);
        return new ComponentMask(newMask);
    }

    /**
     * Clear a component bit from the mask
     */
    public ComponentMask clear(int componentId) {
        BitSet newMask = (BitSet) mask.clone();
        newMask.clear(componentId);
        return new ComponentMask(newMask);
    }

    /**
     * Check if a component is present in the mask
     */
    public boolean has(int componentId) {
        return mask.get(componentId);
    }

    /**
     * Check if this mask contains all components from another mask
     */
    public boolean contains(ComponentMask other) {
        BitSet intersection = (BitSet) mask.clone();
        intersection.and(other.mask);
        return intersection.equals(other.mask);
    }

    /**
     * Get the number of components in this mask
     */
    public int cardinality() {
        return mask.cardinality();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ComponentMask that = (ComponentMask) o;
        return mask.equals(that.mask);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "ComponentMask{" + mask + '}';
    }

    /**
     * Create a builder for fluent API
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final BitSet mask = new BitSet();

        public Builder with(int componentId) {
            mask.set(componentId);
            return this;
        }

        public ComponentMask build() {
            return new ComponentMask(mask);
        }
    }
}
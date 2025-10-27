package com.ethnicthv.ecs.archetype;

import java.util.BitSet;

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
     * Optimized: return true if this mask is a superset of other (WITH semantics).
     */
    public boolean containsAll(ComponentMask other) {
        // other - this == empty ?
        BitSet diff = (BitSet) other.mask.clone();
        diff.andNot(this.mask);
        return diff.isEmpty();
    }

    /**
     * Optimized: return true if this mask shares at least one bit with other (ANY semantics).
     */
    public boolean intersects(ComponentMask other) {
        return this.mask.intersects(other.mask);
    }

    /**
     * Optimized: return true if this mask has no bits in common with other (WITHOUT semantics).
     */
    public boolean containsNone(ComponentMask other) {
        return !this.mask.intersects(other.mask);
    }

    /**
     * Get the number of components in this mask
     */
    public int cardinality() {
        return mask.cardinality();
    }

    /**
     * Return all set component IDs in ascending order.
     */
    public int[] toComponentIdArray() {
        int count = mask.cardinality();
        int[] ids = new int[count];
        int idx = 0;
        for (int bit = mask.nextSetBit(0); bit >= 0; bit = mask.nextSetBit(bit + 1)) {
            ids[idx++] = bit;
        }
        return ids;
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
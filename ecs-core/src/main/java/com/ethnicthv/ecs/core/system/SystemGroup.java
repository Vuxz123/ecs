package com.ethnicthv.ecs.core.system;

import java.util.Objects;

/**
 * Represents a phase in the game loop execution pipeline.
 * <p>
 * Users can define their own groups by instantiating this class.
 * Groups are sorted by priority (lower runs first).
 */
public record SystemGroup(String name, int priority, UpdateMode mode) implements Comparable<SystemGroup> {

    public SystemGroup {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mode, "mode");
    }

    @Override
    public int compareTo(SystemGroup other) {
        // Sort by priority first
        int cmp = Integer.compare(this.priority, other.priority);
        if (cmp != 0) return cmp;
        // Tie-breaker by name to maintain stable sort
        return this.name.compareTo(other.name);
    }

    // === Define Standard Groups for Convenience ===
    public static final SystemGroup INPUT = new SystemGroup("Input", 0, UpdateMode.VARIABLE);
    public static final SystemGroup SIMULATION = new SystemGroup("Simulation", 1000, UpdateMode.FIXED);
    public static final SystemGroup PHYSICS = new SystemGroup("Physics", 2000, UpdateMode.FIXED);
    public static final SystemGroup RENDER = new SystemGroup("Render", 3000, UpdateMode.VARIABLE);
    public static final SystemGroup CLEANUP = new SystemGroup("Cleanup", 4000, UpdateMode.VARIABLE);
}

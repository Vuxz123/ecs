package com.ethnicthv.ecs.core.system;

/**
 * Defines when a group of systems should execute.
 */
public enum UpdateMode {
    /**
     * Runs in the variable time step loop (every frame).
     * Use for: Input, Rendering, Interpolation, UI.
     */
    VARIABLE,

    /**
     * Runs in the fixed time step loop (deterministic).
     * Use for: Physics, Simulation, Gameplay Logic, AI.
     */
    FIXED
}


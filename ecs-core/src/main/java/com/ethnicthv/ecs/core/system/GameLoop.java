package com.ethnicthv.ecs.core.system;

/**
 * GameLoop - fixed-timestep loop coordinator built on top of {@link SystemManager}.
 *
 * Implements the "Fix Your Timestep" pattern:
 * - Variable update for all VARIABLE-mode groups every frame.
 * - Fixed-step update for all FIXED-mode groups at a configurable tick rate.
 */
public final class GameLoop {

    private final SystemManager systems;
    private final float fixedDeltaTime; // seconds per physics/simulation tick
    private volatile boolean running = false;

    /**
     * @param systems        the SystemManager controlling execution groups
     * @param targetTickRate desired fixed update rate in Hz (e.g. 60 => 1/60s)
     */
    public GameLoop(SystemManager systems, float targetTickRate) {
        if (systems == null) throw new IllegalArgumentException("systems must not be null");
        if (targetTickRate <= 0f) throw new IllegalArgumentException("targetTickRate must be > 0");
        this.systems = systems;
        this.fixedDeltaTime = 1.0f / targetTickRate;
    }

    /**
     * Convenience constructor with default 60 Hz fixed tick rate.
     */
    public GameLoop(SystemManager systems) {
        this(systems, 60.0f);
    }

    /**
     * Start the main loop and block until {@link #stop()} is called from another thread
     * or the current thread is interrupted.
     */
    public void run() {
        running = true;

        double accumulator = 0.0;
        long previousTime = System.nanoTime();

        while (running && !Thread.currentThread().isInterrupted()) {
            long now = System.nanoTime();
            double frameTime = (now - previousTime) / 1_000_000_000.0; // seconds
            previousTime = now;

            // Clamp to avoid spiral-of-death if the app hitches
            if (frameTime > 0.25) frameTime = 0.25;

            accumulator += frameTime;
            float deltaTime = (float) frameTime;

            // Fixed-step update: all FIXED groups first
            while (accumulator >= fixedDeltaTime) {
                for (SystemGroup group : systems.getFixedGroups()) {
                    systems.updateGroup(group, fixedDeltaTime);
                }
                accumulator -= fixedDeltaTime;
            }

            // Then variable update: all VARIABLE groups
            for (SystemGroup group : systems.getVariableGroups()) {
                systems.updateGroup(group, deltaTime);
            }

            // Optional: yield to avoid 100% CPU spin if nothing else throttles
            Thread.onSpinWait();
        }
    }

    /**
     * Request the loop to stop gracefully. The current iteration will finish and then exit.
     */
    public void stop() {
        running = false;
    }
}

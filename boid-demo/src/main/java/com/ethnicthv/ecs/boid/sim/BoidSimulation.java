package com.ethnicthv.ecs.boid.sim;

public final class BoidSimulation {
    private final SimulationConfig config;

    public BoidSimulation(SimulationConfig config) {
        this.config = config;
    }

    public void bootstrap() {
        // Phase 0 only wires the application shell. ECS world setup starts in Phase 1.
        if (config.width() <= 0 || config.height() <= 0) {
            throw new IllegalArgumentException("Window dimensions must be positive");
        }
    }

    public void shutdown() {
        // Reserved for future ECS world teardown.
    }
}

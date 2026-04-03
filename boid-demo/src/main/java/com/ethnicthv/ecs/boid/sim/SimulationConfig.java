package com.ethnicthv.ecs.boid.sim;

public record SimulationConfig(
    int width,
    int height,
    String title,
    float backgroundRed,
    float backgroundGreen,
    float backgroundBlue
) {
    public static SimulationConfig defaultConfig() {
        return new SimulationConfig(
            1280,
            720,
            "ECS Boid Demo - Phase 0",
            0.06f,
            0.08f,
            0.12f
        );
    }
}

package com.ethnicthv.ecs.boid.sim;

public enum SteeringExecutionMode {
    SEQUENTIAL,
    PARALLEL;

    public SteeringExecutionMode toggle() {
        return this == SEQUENTIAL ? PARALLEL : SEQUENTIAL;
    }
}

package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.Component;

/**
 * Velocity component with VX and VY values
 * Uses annotations to define memory layout
 */
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class VelocityComponent implements Component {

    @Component.Field
    public float vx;

    @Component.Field
    public float vy;

    // No-arg constructor required for reflection
    public VelocityComponent() {}

    public VelocityComponent(float vx, float vy) {
        this.vx = vx;
        this.vy = vy;
    }

    @Override
    public String toString() {
        return "Velocity(" + vx + ", " + vy + ")";
    }
}

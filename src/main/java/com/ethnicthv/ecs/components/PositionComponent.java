package com.ethnicthv.ecs.components;

import com.ethnicthv.ecs.core.Component;

/**
 * Position component with X and Y coordinates
 * Uses annotations to define memory layout
 */
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class PositionComponent implements Component {

    @Component.Field
    public float x;

    @Component.Field
    public float y;

    // No-arg constructor required for reflection
    public PositionComponent() {}

    public PositionComponent(float x, float y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public String toString() {
        return "Position(" + x + ", " + y + ")";
    }
}

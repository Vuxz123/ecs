package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.Component;

/**
 * Position component with X and Y coordinates
 * Uses annotations to define memory layout
 */
@Component.Unmanaged
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class PositionComponent implements Component {

    @Component.Field
    public float x;

    @Component.Field
    public float y;

    @Component.Field
    public Vector3f pos;
}

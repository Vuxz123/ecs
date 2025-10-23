package com.ethnicthv.ecs.components;

import com.ethnicthv.ecs.core.Component;

/**
 * Transform component with position, rotation, and scale
 * Demonstrates PADDING layout with alignment requirements
 */
@Component.Layout(Component.LayoutType.PADDING)
public class TransformComponent implements Component {

    @Component.Field(alignment = 4)
    public float x;

    @Component.Field(alignment = 4)
    public float y;

    @Component.Field(alignment = 4)
    public float z;

    @Component.Field(alignment = 4)
    public float rotation;

    @Component.Field(alignment = 4)
    public float scaleX;

    @Component.Field(alignment = 4)
    public float scaleY;

    public TransformComponent() {}

    public TransformComponent(float x, float y, float z, float rotation, float scaleX, float scaleY) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.rotation = rotation;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    @Override
    public String toString() {
        return String.format("Transform(pos=[%.2f, %.2f, %.2f], rot=%.2f, scale=[%.2f, %.2f])",
                           x, y, z, rotation, scaleX, scaleY);
    }
}


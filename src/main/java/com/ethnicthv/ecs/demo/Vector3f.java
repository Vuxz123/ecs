package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.Component;

@Component.Layout(Component.LayoutType.EXPLICIT)
public class Vector3f {
    @Component.Field(offset = 0)
    public float x;

    @Component.Field(offset = 4)
    public float y;

    @Component.Field(offset = 8)
    public float z;
}

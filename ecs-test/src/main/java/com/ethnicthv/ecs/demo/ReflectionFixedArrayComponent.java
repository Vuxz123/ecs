package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.Component;

@Component.Unmanaged
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public final class ReflectionFixedArrayComponent implements Component {
    @Field(length = 4)
    public int samples;

    @Field
    public int count;
}

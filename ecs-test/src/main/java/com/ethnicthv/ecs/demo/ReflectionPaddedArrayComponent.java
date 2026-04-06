package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.Component;

@Component.Unmanaged
@Component.Layout(Component.LayoutType.PADDING)
public final class ReflectionPaddedArrayComponent implements Component {
    @Field(length = 3)
    public byte samples;

    @Field
    public long tail;
}

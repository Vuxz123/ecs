package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.Component;

@Component.Unmanaged
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public final class LegacySizedScalarComponent implements Component {
    @Component.Field(size = 2)
    public int id;
}

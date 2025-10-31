package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.components.Component;

@Component.Layout(Component.LayoutType.SEQUENTIAL)
public final class TestComponent1 implements Component {
    @Field(offset = 0, size = 1, alignment = 1)
    int value;
}

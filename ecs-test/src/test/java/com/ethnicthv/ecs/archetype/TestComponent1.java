package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.components.Component;

@Component.Layout(Component.LayoutType.SEQUENTIAL)
public final class TestComponent1 implements Component {
    @Component.Field(offset = 0, size = 4, alignment = 4)
    int value;
}

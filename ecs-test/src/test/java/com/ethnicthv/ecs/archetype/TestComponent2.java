package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.components.Component;

@Component.Layout(Component.LayoutType.EXPLICIT)
public final class TestComponent2 implements Component {
    @Field(offset = 0, size = 4, alignment = 4)
    float data;
}

package com.ethnicthv.ecs.archetype;
import com.ethnicthv.ecs.core.components.Component;

@Component.Layout(Component.LayoutType.PADDING)
public final class TestComponent3 implements Component {
    @Field(offset = 0, size = 8, alignment = 8)
    long id;
}

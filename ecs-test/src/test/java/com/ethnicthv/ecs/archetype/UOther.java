package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.components.Component;

@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class UOther implements Component {
    @Field
    int b;
}

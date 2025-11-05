package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.components.Component;

@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class U1 implements Component {
    @Field
    int a;
}

package com.ethnicthv.ecs.bench;

import com.ethnicthv.ecs.core.components.Component;

@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class Pos implements Component {
    @Field
    float x;
    @Field
    float y;
}

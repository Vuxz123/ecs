package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.core.components.Component;

@Component.Unmanaged
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public final class Position3 implements Component {
    @Component.Field public float x;
    @Component.Field public float y;
    @Component.Field public float z;
}

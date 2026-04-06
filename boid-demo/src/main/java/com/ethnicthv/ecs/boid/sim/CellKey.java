package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.core.components.Component;

@Component.Unmanaged
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public final class CellKey implements Component {
    @Component.Field
    public int cellId;
}

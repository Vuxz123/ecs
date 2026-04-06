package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.core.components.Component;

@Component.Unmanaged
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public final class NeighborBuffer implements Component {
    public static final int CAPACITY = 64;

    @Field(length = CAPACITY)
    public int neighbors;

    @Field(length = CAPACITY)
    public byte offsetPacks;

    @Field
    public int count;
}

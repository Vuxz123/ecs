package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.components.Component;

/**
 * Unmanaged shared interface used for tests.
 */
@Component.Shared
@Component.Unmanaged
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class Region implements Component {
    @Field(size = 2)
    public int id;
}

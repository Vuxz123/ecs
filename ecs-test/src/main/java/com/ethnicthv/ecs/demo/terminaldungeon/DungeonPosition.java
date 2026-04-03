package com.ethnicthv.ecs.demo.terminaldungeon;

import com.ethnicthv.ecs.core.components.Component;

@Component.Unmanaged
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class DungeonPosition implements Component {
    @Component.Field public int x;
    @Component.Field public int y;
}

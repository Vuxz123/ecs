package com.ethnicthv.ecs.demo.terminaldungeon;

import com.ethnicthv.ecs.core.components.Component;

@Component.Unmanaged
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class PlayerState implements Component {
    @Component.Field public int hp;
    @Component.Field public int score;
}

package com.ethnicthv.ecs.demo.terminaldungeon;

import com.ethnicthv.ecs.core.components.Component;

@Component.Unmanaged
@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class EnemyState implements Component {
    @Component.Field public int damage;
}

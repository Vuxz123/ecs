package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.Component;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.components.IBindableHandle;
import com.ethnicthv.ecs.demo.PositionComponent;
import com.ethnicthv.ecs.demo.PositionComponentHandle;
import com.ethnicthv.ecs.generated.GeneratedComponents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class URaw implements Component {
    @Field
    int a;

}

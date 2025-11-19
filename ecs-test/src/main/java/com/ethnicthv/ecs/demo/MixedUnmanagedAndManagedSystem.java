package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Component;
import com.ethnicthv.ecs.core.system.annotation.Query;

class MixedUnmanagedAndManagedSystem extends BaseSystem {
    private IGeneratedQuery mixedEntities;

    @Override
    public void onUpdate(float deltaTime) {
        if (mixedEntities != null) {
            mixedEntities.runQuery();
        }
    }

    @Query(
            fieldInject = "mixedEntities",
            mode = ExecutionMode.PARALLEL,
            with = {PositionComponent.class, HealthComponent.class}
    )
    private void query(
            @Component(type = PositionComponent.class) PositionComponentHandle positionHandle,
            NameComponent nameComponent
    ) {
        // Update position
        float x = positionHandle.getX();
        float y = positionHandle.getY();
        x += 1.0f;
        y += 1.0f;
        positionHandle.setX(x);
        positionHandle.setY(y);

        // log name from managed component
        System.out.println("Entity Name: " + nameComponent.name);
    }
}

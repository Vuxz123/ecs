package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Component;
import com.ethnicthv.ecs.core.system.annotation.Query;

public final class ResetAccelerationSystem extends BaseSystem {
    private IGeneratedQuery resetQuery;

    @Override
    public void onUpdate(float deltaTime) {
        if (resetQuery != null) {
            resetQuery.runQuery();
        }
    }

    @Query(
        fieldInject = "resetQuery",
        mode = ExecutionMode.SEQUENTIAL,
        with = {Acceleration3.class}
    )
    private void query(
        @Component(type = Acceleration3.class) Acceleration3Handle acceleration
    ) {
        acceleration.setX(0.0f);
        acceleration.setY(0.0f);
        acceleration.setZ(0.0f);
    }
}

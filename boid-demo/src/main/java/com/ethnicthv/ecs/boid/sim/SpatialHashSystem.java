package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Component;
import com.ethnicthv.ecs.core.system.annotation.Id;
import com.ethnicthv.ecs.core.system.annotation.Query;

public final class SpatialHashSystem extends BaseSystem {
    private final SpatialHashGrid spatialHash;

    private IGeneratedQuery spatialQuery;

    public SpatialHashSystem(SpatialHashGrid spatialHash) {
        this.spatialHash = spatialHash;
    }

    @Override
    public void onUpdate(float deltaTime) {
        if (spatialQuery == null) {
            return;
        }

        spatialHash.beginFrame();
        spatialQuery.runQuery();
        spatialHash.finishFrame();
    }

    @Query(
        fieldInject = "spatialQuery",
        mode = ExecutionMode.SEQUENTIAL,
        with = {Position3.class, Velocity3.class, CellKey.class}
    )
    private void query(
        @Id int entityId,
        @Component(type = Position3.class) Position3Handle position,
        @Component(type = Velocity3.class) Velocity3Handle velocity,
        @Component(type = CellKey.class) CellKeyHandle cell
    ) {
        int cellKey = spatialHash.captureBoid(
            entityId,
            position.getX(),
            position.getY(),
            position.getZ(),
            velocity.getX(),
            velocity.getY(),
            velocity.getZ()
        );
        cell.setCellId(cellKey);
    }
}

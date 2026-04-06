package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Component;
import com.ethnicthv.ecs.core.system.annotation.Id;
import com.ethnicthv.ecs.core.system.annotation.Query;

public final class BuildNeighborSystem extends BaseSystem {
    private final SpatialHashGrid spatialHash;

    private IGeneratedQuery neighborBuildQuery;

    public BuildNeighborSystem(SpatialHashGrid spatialHash) {
        this.spatialHash = spatialHash;
    }

    @Override
    public void onUpdate(float deltaTime) {
        if (neighborBuildQuery == null || !spatialHash.beginNeighborBuild()) {
            return;
        }

        neighborBuildQuery.runQuery();
        spatialHash.endNeighborBuild();
    }

    @Query(
        fieldInject = "neighborBuildQuery",
        mode = ExecutionMode.PARALLEL,
        with = {CellKey.class}
    )
    private void query(
        @Id int entityId,
        @Component(type = CellKey.class) CellKeyHandle cell
    ) {
        spatialHash.buildNeighborsForEntity(entityId);
    }
}

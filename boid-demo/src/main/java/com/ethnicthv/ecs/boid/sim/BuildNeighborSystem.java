package com.ethnicthv.ecs.boid.sim;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Component;
import com.ethnicthv.ecs.core.system.annotation.Id;
import com.ethnicthv.ecs.core.system.annotation.Query;

public final class BuildNeighborSystem extends BaseSystem {
    private final SpatialHashGrid spatialHash;
    private int neighborCountIndex = -1;
    private int neighborEntriesIndex = -1;
    private int neighborOffsetPacksIndex = -1;

    private IGeneratedQuery neighborBuildQuery;

    public BuildNeighborSystem(SpatialHashGrid spatialHash) {
        this.spatialHash = spatialHash;
    }

    @Override
    public void onAwake(ArchetypeWorld world) {
        super.onAwake(world);
        neighborCountIndex = world.getComponentManager().getDescriptor(NeighborBuffer.class).getFieldIndex("count");
        neighborEntriesIndex = world.getComponentManager().getDescriptor(NeighborBuffer.class).getFieldIndex("neighbors");
        neighborOffsetPacksIndex = world.getComponentManager().getDescriptor(NeighborBuffer.class).getFieldIndex("offsetPacks");
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
        with = {NeighborBuffer.class}
    )
    private void query(
        @Id int entityId,
        @Component(type = NeighborBuffer.class) ComponentHandle neighborBuffer
    ) {
        spatialHash.buildNeighborsForEntity(entityId, neighborBuffer, neighborCountIndex, neighborEntriesIndex, neighborOffsetPacksIndex);
    }
}

package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.api.archetype.IQueryBuilder;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Component;
import com.ethnicthv.ecs.core.system.annotation.Id;
import com.ethnicthv.ecs.core.system.annotation.Query;

class TeamFilterSystem extends BaseSystem {
    private IGeneratedQuery q;

    @Override
    public void onUpdate(float deltaTime) {
        updateOnlyTeamA();
    }

    void updateOnlyTeamA() {
        // The generated runner will keep stateful filter and reset after run
        if (q instanceof IQueryBuilder b) {
            b.withShared(new TeamShared("A")).build().runQuery();
        } else if (q != null) {
            System.out.println("Warning: Query instance is not a builder, cannot set shared filter dynamically.");
            q.runQuery();
        }
    }

    @Query(
            fieldInject = "q",
            mode = ExecutionMode.SEQUENTIAL,
            with = {PositionComponent.class, VelocityComponent.class}
    )
    private void query(
            @Id int entityId,
            @Component(type = PositionComponent.class) PositionComponentHandle pos,
            @Component(type = VelocityComponent.class) VelocityComponentHandle vel,
            @Component(type = IndexComponent.class) IndexComponentHandle index
    ) {
        float x = pos.getX();
        float y = pos.getY();
        x += vel.getVx() * 0.05f;
        y += vel.getVy() * 0.05f;
        pos.setX(x);
        pos.setY(y);

        if (index.getIndex() % 2 == 1) {
            System.out.println("Team A Entity " + entityId + "with Index " + index.getIndex() + " moved to (" + x + ", " + y + ")");
        }

    }
}

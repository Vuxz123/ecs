package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.annotation.Component;
import com.ethnicthv.ecs.core.system.annotation.Query;

/**
 * Health regeneration system using SEQUENTIAL execution.
 * Demonstrates that SEQUENTIAL is still the default and works as expected.
 */
class HealthRegenerationSystem extends BaseSystem {
    private IGeneratedQuery healthyEntities;

    @Override
    public void onUpdate(float deltaTime) {
        if (healthyEntities != null) {
            healthyEntities.runQuery();
        }
    }

    @Query(
            fieldInject = "healthyEntities",
            mode = ExecutionMode.SEQUENTIAL, // Explicit but could be omitted (it's default)
            with = HealthComponent.class
    )
    private void query(
            @Component(type = HealthComponent.class) HealthComponentHandle healthHandle
    ) {
        int health = healthHandle.getCurrentHealth();
        int maxHealth = healthHandle.getMaxHealth();

        if (health < maxHealth) {
            health = Math.min(maxHealth, health + (int) (10 * 0.1f));
            healthHandle.setCurrentHealth(health);
        }
    }
}

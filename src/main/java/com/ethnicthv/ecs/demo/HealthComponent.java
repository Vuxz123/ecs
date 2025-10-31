package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.Component;

/**
 * Health component with various data types
 * Demonstrates EXPLICIT layout with custom offsets
 */
@Component.Layout(value = Component.LayoutType.EXPLICIT, size = 32)
public class HealthComponent implements Component {

    @Component.Field(offset = 0)
    public int currentHealth;

    @Component.Field(offset = 4)
    public int maxHealth;

    @Component.Field(offset = 8)
    public float regenerationRate;

    @Component.Field(offset = 12)
    public boolean isDead;

    @Component.Field(offset = 16)
    public long lastDamageTime;

    @Component.Field(offset = 24)
    public byte armor;

    public HealthComponent() {}

    public HealthComponent(int maxHealth, float regenerationRate) {
        this.currentHealth = maxHealth;
        this.maxHealth = maxHealth;
        this.regenerationRate = regenerationRate;
        this.isDead = false;
        this.lastDamageTime = 0;
        this.armor = 0;
    }

    @Override
    public String toString() {
        return String.format("Health(%d/%d, regen=%.2f, dead=%s, armor=%d)",
                           currentHealth, maxHealth, regenerationRate, isDead, armor);
    }
}


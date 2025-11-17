package com.ethnicthv.ecs.core.system;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;

/**
 * Convenience base class for ECS systems.
 *
 * Provides:
 * - stored reference to {@link ArchetypeWorld} after {@link #onAwake(ArchetypeWorld)}
 * - default {@link #onDispose()} implementation (no-op)
 * - enabled flag with getters/setters
 *
 * Subclasses must implement {@link #onUpdate(float)}.
 */
public abstract class BaseSystem implements ISystem {

    protected ArchetypeWorld world;
    private boolean enabled = true;

    @Override
    public void onAwake(ArchetypeWorld world) {
        this.world = world;
    }

    @Override
    public void onDispose() {
        // Default: no-op. Override if the system owns external resources.
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // Note: onUpdate(float deltaTime) remains abstract to force subclasses
    // to provide their own update logic.
}


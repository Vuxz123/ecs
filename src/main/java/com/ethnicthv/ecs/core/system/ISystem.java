package com.ethnicthv.ecs.core.system;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;

/**
 * ISystem - contract for ECS systems managed by {@link SystemManager}.
 *
 * Systems follow a simple lifecycle:
 * <ul>
 *     <li>{@link #onAwake(ArchetypeWorld)} - called once when the system is registered.</li>
 *     <li>{@link #onUpdate(float)} - called every frame while the system is enabled.</li>
 *     <li>{@link #onDispose()} - called when the world is being closed or the system is removed.</li>
 * </ul>
 */
public interface ISystem {

    /** Called once when the system is registered with a world. */
    void onAwake(ArchetypeWorld world);

    /**
     * Called once per frame / tick while the system is enabled.
     *
     * @param deltaTime time in seconds since last update (can be fixed or variable step)
     */
    void onUpdate(float deltaTime);

    /** Called when the system is being disposed (world closing or system removal). */
    void onDispose();

    /** Whether this system should currently run in the update loop. */
    boolean isEnabled();

    /** Enable or disable this system. Disabled systems are skipped in update(). */
    void setEnabled(boolean enabled);
}

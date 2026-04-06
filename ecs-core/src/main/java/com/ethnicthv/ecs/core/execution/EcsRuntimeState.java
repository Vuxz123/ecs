package com.ethnicthv.ecs.core.execution;

/**
 * Lifecycle state for the optional ECS runtime thread wrapper.
 */
public enum EcsRuntimeState {
    NEW,
    RUNNING,
    STOPPING,
    STOPPED
}

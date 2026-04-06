package com.ethnicthv.ecs.core.execution;

/**
 * Controls which thread owns the outer ECS system update loop.
 */
public enum SystemThreadMode {
    /**
     * Systems run on the caller thread via direct update calls or {@code GameLoop.run()}.
     */
    MAIN_THREAD,

    /**
     * Systems run on a dedicated simulation thread managed by the ECS runtime controller.
     */
    DEDICATED_THREAD
}

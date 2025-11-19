package com.ethnicthv.ecs.core.components;

/**
 * Marker for generated typed handles to allow pooling.
 */
public interface IBindableHandle {
    void __bind(ComponentHandle handle);
}


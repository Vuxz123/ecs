package com.ethnicthv.ecs.generated;

import com.ethnicthv.ecs.core.components.HandlePool;
import com.ethnicthv.ecs.core.components.IBindableHandle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Central registry of per-type HandlePool instances. Thread-safe.
 */
public final class GeneratedHandlePools {
    private static final Map<Class<? extends IBindableHandle>, HandlePool<? extends IBindableHandle>> POOLS = new ConcurrentHashMap<>();

    private GeneratedHandlePools() {}

    @SuppressWarnings("unchecked")
    public static <T extends IBindableHandle> HandlePool<T> poolFor(Class<T> handleClass, Supplier<T> factory) {
        return (HandlePool<T>) POOLS.computeIfAbsent(handleClass, k -> new HandlePool<>(factory));
    }
}


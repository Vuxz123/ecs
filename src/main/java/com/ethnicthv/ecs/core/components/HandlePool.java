package com.ethnicthv.ecs.core.components;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Thread-local pool for typed handle instances to minimize allocations.
 */
public final class HandlePool<T extends IBindableHandle> {
    private final Supplier<T> factory;
    private final ThreadLocal<Deque<T>> local;

    public HandlePool(Supplier<T> factory) {
        this.factory = Objects.requireNonNull(factory);
        this.local = ThreadLocal.withInitial(ArrayDeque::new);
    }

    public T acquire() {
        Deque<T> dq = local.get();
        T t = dq.pollFirst();
        if (t == null) t = factory.get();
        return t;
    }

    public void release(T h) {
        if (h == null) return;
        local.get().offerFirst(h);
    }
}


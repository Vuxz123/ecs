package com.ethnicthv.ecs.core.components;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Thread-safe global store for managed (on-heap) component instances.
 * Stores objects and returns a stable integer index (a "ticket").
 * Indices are recycled via a lock-free free list.
 */
public final class ManagedComponentStore {
    private static final int DEFAULT_CAPACITY = 1024;

    private final Object resizeLock = new Object();
    private volatile AtomicReferenceArray<Object> array;
    private final ConcurrentLinkedQueue<Integer> freeList = new ConcurrentLinkedQueue<>();
    private final AtomicInteger nextIndex = new AtomicInteger(0);

    public ManagedComponentStore() {
        this(DEFAULT_CAPACITY);
    }

    public ManagedComponentStore(int initialCapacity) {
        if (initialCapacity <= 0) initialCapacity = DEFAULT_CAPACITY;
        this.array = new AtomicReferenceArray<>(initialCapacity);
    }

    /**
     * Store an object and return its stable index.
     */
    public int store(Object componentInstance) {
        Objects.requireNonNull(componentInstance, "componentInstance");
        Integer idx = freeList.poll();
        if (idx != null) {
            array.set(idx, componentInstance);
            return idx;
        }
        // allocate a fresh slot, resizing if needed
        while (true) {
            int i = nextIndex.getAndIncrement();
            AtomicReferenceArray<Object> arr = this.array;
            if (i < arr.length()) {
                arr.set(i, componentInstance);
                return i;
            }
            // need resize; roll back claim and resize under lock then retry
            // The roll-back is approximate; over-claims will be compensated by growing array.
            ensureCapacity(i + 1);
        }
    }

    /**
     * Get the object at index. Returns null if released or out of bounds.
     */
    public Object get(int index) {
        AtomicReferenceArray<Object> arr = this.array;
        if (index < 0 || index >= arr.length()) return null;
        return arr.get(index);
    }

    /**
     * Release an index back to the free list. Idempotent.
     */
    public void release(int index) {
        AtomicReferenceArray<Object> arr = this.array;
        if (index < 0 || index >= arr.length()) return;
        Object prev = arr.getAndSet(index, null);
        if (prev != null) {
            freeList.offer(index);
        }
    }

    private void ensureCapacity(int minCapacity) {
        AtomicReferenceArray<Object> arr = this.array;
        if (minCapacity <= arr.length()) return;
        synchronized (resizeLock) {
            AtomicReferenceArray<Object> cur = this.array;
            if (minCapacity <= cur.length()) return; // double-checked
            int newCap = Math.max(minCapacity, Math.max(1024, cur.length() << 1));
            AtomicReferenceArray<Object> next = new AtomicReferenceArray<>(newCap);
            for (int i = 0; i < cur.length(); i++) {
                next.set(i, cur.get(i));
            }
            this.array = next;
        }
    }
}


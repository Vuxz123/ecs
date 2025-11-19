package com.ethnicthv.ecs.core.components;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe store for de-duplicating managed shared component values.
 * It maps a value to a stable index with reference counting.
 */
public final class SharedComponentStore {
    private final ConcurrentHashMap<Object, Integer> valueToIndex = new ConcurrentHashMap<>();
    private final ArrayList<Object> indexToValue = new ArrayList<>();
    private volatile int[] refCounts = new int[16];
    private final ConcurrentLinkedQueue<Integer> freeIndices = new ConcurrentLinkedQueue<>();

    private final Object resizeLock = new Object();

    /**
     * Get or add an index for the provided value. If value already exists, its refCount is incremented atomically.
     * If it's a new value, a new or recycled index is created, stored, and refCount set to 1.
     */
    public int getOrAddSharedIndex(Object value) {
        Objects.requireNonNull(value, "value");
        return valueToIndex.compute(value, (k, existingIdx) -> {
            synchronized (resizeLock) {
                if (existingIdx == null) {
                    Integer reuse = freeIndices.poll();
                    int index;
                    if (reuse != null) {
                        index = reuse;
                        ensureRefCapacity(index + 1);
                        if (index < indexToValue.size()) {
                            indexToValue.set(index, k);
                        } else {
                            while (indexToValue.size() < index) indexToValue.add(null);
                            indexToValue.add(k);
                        }
                    } else {
                        index = indexToValue.size();
                        indexToValue.add(k);
                        ensureRefCapacity(index + 1);
                    }
                    refCounts[index] = 1;
                    return index;
                } else {
                    int idx = existingIdx;
                    ensureRefCapacity(idx + 1);
                    refCounts[idx]++;
                    return idx;
                }
            }
        });
    }

    /**
     * Release a previously acquired shared index. When refCount reaches zero, the value is removed and index recycled.
     */
    public void releaseSharedIndex(int index) {
        synchronized (resizeLock) {
            if (index < 0 || index >= indexToValue.size()) return;
            Object value = indexToValue.get(index);
            if (value == null) return; // already removed
            int rc = --refCounts[index];
            if (rc > 0) return;
            // remove entry and mapping
            indexToValue.set(index, null);
            valueToIndex.remove(value, index);
            // advertise recycled index
            freeIndices.offer(index);
        }
    }

    /**
     * Find existing index for a value without creating a new entry or changing ref counts. Returns -1 if absent.
     */
    public int findIndex(Object value) {
        Integer idx = valueToIndex.get(value);
        return idx != null ? idx : -1;
    }

    private void ensureRefCapacity(int min) {
        int[] cur = refCounts;
        if (min <= cur.length) return;
        int newLen = Math.max(min, Math.max(16, cur.length << 1));
        int[] next = new int[newLen];
        System.arraycopy(cur, 0, next, 0, cur.length);
        refCounts = next;
    }

    // Testing/helpers
    public Object getValue(int index) {
        synchronized (resizeLock) {
            return (index >= 0 && index < indexToValue.size()) ? indexToValue.get(index) : null;
        }
    }

    public int getRefCount(int index) {
        synchronized (resizeLock) {
            return (index >= 0 && index < indexToValue.size()) ? refCounts[index] : 0;
        }
    }
}

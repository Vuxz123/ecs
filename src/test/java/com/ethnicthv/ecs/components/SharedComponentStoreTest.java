package com.ethnicthv.ecs.components;

import com.ethnicthv.ecs.core.components.SharedComponentStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SharedComponentStoreTest {

    @Test
    void deduplicatesEqualValuesAndCountsRefs() {
        SharedComponentStore store = new SharedComponentStore();
        String v1a = "abc";
        String v1b = "abc"; // equals to v1a

        int idx1 = store.getOrAddSharedIndex(v1a);
        assertEquals(1, store.getRefCount(idx1));
        assertEquals("abc", store.getValue(idx1));

        int idx2 = store.getOrAddSharedIndex(v1b);
        assertEquals(idx1, idx2, "Equal values should map to same index");
        assertEquals(2, store.getRefCount(idx1));

        // Release once
        store.releaseSharedIndex(idx1);
        assertEquals(1, store.getRefCount(idx1));

        // Release second time -> remove
        store.releaseSharedIndex(idx1);
        assertNull(store.getValue(idx1));

        // Re-add equal value should allocate an index (may reuse old index)
        int idx3 = store.getOrAddSharedIndex("abc");
        assertNotNull(store.getValue(idx3));
        assertEquals(1, store.getRefCount(idx3));
    }
}


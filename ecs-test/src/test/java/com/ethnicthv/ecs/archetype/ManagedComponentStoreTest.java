package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.components.ManagedComponentStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class ManagedComponentStoreTest {

    @Test
    void storeGetRelease_basic() {
        ManagedComponentStore store = new ManagedComponentStore(4);
        int a = store.store("A");
        int b = store.store("B");
        assertNotEquals(a, b);
        assertEquals("A", store.get(a));
        assertEquals("B", store.get(b));
        store.release(a);
        assertNull(store.get(a));
        int c = store.store("C");
        // c can reuse 'a' slot
        assertEquals("C", store.get(c));
    }

    @Test
    void concurrent_store_and_release() throws InterruptedException {
        ManagedComponentStore store = new ManagedComponentStore(8);
        int threads = 8;
        int ops = 1000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try (ExecutorService es = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                es.submit(() -> {
                    try {
                        start.await();
                        List<Integer> tickets = new ArrayList<>();
                        for (int i = 0; i < ops; i++) {
                            int id = store.store("x" + i);
                            tickets.add(id);
                            if ((i & 1) == 0) {
                                // release half of them
                                store.release(id);
                            }
                        }
                        // release the rest
                        for (int id : tickets) {
                            store.release(id);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void deterministicReuse_smallCapacity() {
        ManagedComponentStore store = new ManagedComponentStore(2);
        int t0 = store.store("A");
        int t1 = store.store("B");
        assertNotEquals(t0, t1);
        // Release first, it should be reused by next store
        store.release(t0);
        assertNull(store.get(t0));
        int t2 = store.store("C");
        assertEquals(t0, t2, "Expected first released ticket to be reused");
        // Release second, then reused
        store.release(t1);
        assertNull(store.get(t1));
        int t3 = store.store("D");
        assertEquals(t1, t3, "Expected second released ticket to be reused");
    }
}

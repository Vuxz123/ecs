package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.ComponentDescriptor;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.components.HandlePool;
import com.ethnicthv.ecs.generated.GeneratedHandlePools;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

public class HandlePoolTest {
    @Test
    void threadLocalPool_acquire_release_handles() {
        HandlePool<PositionComponentHandle> pool = GeneratedHandlePools.poolFor(PositionComponentHandle.class, PositionComponentHandle::new);
        PositionComponentHandle h1 = pool.acquire();
        PositionComponentHandle h2 = pool.acquire();
        assertNotSame(h1, h2);
        pool.release(h1);
        pool.release(h2);
        PositionComponentHandle h3 = pool.acquire();
        PositionComponentHandle h4 = pool.acquire();
        assertSame(h2, h3); // LIFO on same thread
        assertSame(h1, h4);
    }

    @Test
    void pooledHandle_can_bind_and_use() {
        HandlePool<PositionComponentHandle> pool = GeneratedHandlePools.poolFor(PositionComponentHandle.class, PositionComponentHandle::new);
        PositionComponentHandle h = pool.acquire();
        Arena arena = Arena.ofShared();
        try {
            ComponentDescriptor d = PositionComponentMeta.DESCRIPTOR;
            MemorySegment seg = arena.allocate(d.getTotalSize());
            ComponentHandle raw = new ComponentHandle(seg, d);
            h.__bind(raw);
            h.setX(7.0f);
            assertEquals(7.0f, h.getX());
        } finally {
            arena.close();
            pool.release(h);
        }
    }
}

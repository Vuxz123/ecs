package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.api.archetype.IQuery;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.SystemManager;
import com.ethnicthv.ecs.core.system.annotation.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class MixedManagedUnmanagedRunnerTest {

    static class SysSequential {
        IGeneratedQuery q;
        final AtomicInteger count = new AtomicInteger();
        final Set<String> names = ConcurrentHashMap.newKeySet();

        void update(){ q.runQuery(); }

        @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = { Profile.class, U1.class })
        private void query(@com.ethnicthv.ecs.core.system.annotation.Component(type = Profile.class) Profile profile,
                           @com.ethnicthv.ecs.core.system.annotation.Component(type = U1.class) ComponentHandle u1) {
            names.add(profile.name);
            int idx = u1.resolveFieldIndex("a");
            u1.setInt(idx, 7);
            count.incrementAndGet();
        }
    }

    static class SysParallel {
        IGeneratedQuery q;
        final AtomicInteger count = new AtomicInteger();
        final Set<String> names = ConcurrentHashMap.newKeySet();
        void update(){ q.runQuery(); }

        @Query(fieldInject = "q", mode = ExecutionMode.PARALLEL, with = { Profile.class, U1.class })
        private void query(@com.ethnicthv.ecs.core.system.annotation.Component(type = Profile.class) Profile profile,
                           @com.ethnicthv.ecs.core.system.annotation.Component(type = U1.class) ComponentHandle u1) {
            names.add(profile.name);
            int idx = u1.resolveFieldIndex("a");
            u1.setInt(idx, 9);
            count.incrementAndGet();
        }
    }

    @Test
    void mixed_params_sequential_runner() {
        ComponentManager cm = new ComponentManager();
        ArchetypeWorld world = new ArchetypeWorld(cm);
        SystemManager sm = new SystemManager(world);
        world.registerComponent(Profile.class);
        world.registerComponent(U1.class);

        List<Integer> eids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int e = world.createEntity(Profile.class, U1.class);
            eids.add(e);
            world.setManagedComponent(e, new Profile("P"+i));
            // Unmanaged U1 memory already allocated; values default to 0.
        }

        SysSequential sys = new SysSequential();
        sm.registerSystem(sys);
        sys.update();

        assertEquals(8, sys.count.get());
        assertEquals(8, sys.names.size());
        for (int i = 0; i < 8; i++) assertTrue(sys.names.contains("P"+i));

        // Validate unmanaged writes happened
        for (int e : eids) {
            var seg = world.getComponent(e, U1.class);
            assertNotNull(seg);
            // Build a handle to read back
            try (ComponentManager.BoundHandle bh = cm.acquireBoundHandle(U1.class, seg)) {
                int idx = bh.handle().resolveFieldIndex("a");
                assertEquals(7, bh.handle().getInt(idx));
            }
        }

        world.close();
    }

    @Test
    void mixed_params_parallel_runner() {
        ComponentManager cm = new ComponentManager();
        ArchetypeWorld world = new ArchetypeWorld(cm);
        SystemManager sm = new SystemManager(world);
        world.registerComponent(Profile.class);
        world.registerComponent(U1.class);

        List<Integer> eids = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int e = world.createEntity(Profile.class, U1.class);
            eids.add(e);
            world.setManagedComponent(e, new Profile("Q"+i));
        }

        SysParallel sys = new SysParallel();
        sm.registerSystem(sys);
        sys.update();

        assertEquals(16, sys.count.get());
        assertEquals(16, sys.names.size());
        for (int i = 0; i < 16; i++) assertTrue(sys.names.contains("Q"+i));

        // Validate unmanaged writes happened
        for (int e : eids) {
            var seg = world.getComponent(e, U1.class);
            assertNotNull(seg);
            try (ComponentManager.BoundHandle bh = cm.acquireBoundHandle(U1.class, seg)) {
                int idx = bh.handle().resolveFieldIndex("a");
                assertEquals(9, bh.handle().getInt(idx));
            }
        }

        world.close();
    }
}

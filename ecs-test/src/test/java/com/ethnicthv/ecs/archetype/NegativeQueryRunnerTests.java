package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.SystemManager;
import com.ethnicthv.ecs.core.system.annotation.Query;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Negative tests to assert graceful handling of misconfigurations. */
public class NegativeQueryRunnerTests {

    static class SysManagedParamButUnmanagedDescriptor extends BaseSystem {
        IGeneratedQuery q;
        void update(){ q.runQuery(); }

        // Declared as managed object parameter but descriptor is unmanaged -> should throw in validateConfig
        @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = { UPlain.class })
        private void query(@com.ethnicthv.ecs.core.system.annotation.Component(type = UPlain.class) UPlain u) {
            // never reached
        }

        @Override
        public void onUpdate(float deltaTime) {
            update();
        }
    }

    static class SysUnmanagedParamMissingRawComponent extends BaseSystem {
        IGeneratedQuery q;
        int count;
        void update(){ q.runQuery(); }

        // Expect URaw present, but we'll create entities without it, only UOther -> zero processed, no exception
        @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = { URaw.class })
        private void query(@com.ethnicthv.ecs.core.system.annotation.Component(type = URaw.class) ComponentHandle raw) {
            count++;
        }

        @Override
        public void onUpdate(float deltaTime) {
            update();
        }
    }

    @Test
    void managed_param_but_unmanaged_descriptor_should_throw() {
        ComponentManager cm = new ComponentManager();
        try (ArchetypeWorld world = new ArchetypeWorld(cm)) {
            world.registerComponent(UPlain.class);

            SysManagedParamButUnmanagedDescriptor sys = new SysManagedParamButUnmanagedDescriptor();
            SystemManager sm = new SystemManager(world);
            sm.registerSystem(sys);

            IllegalStateException ex = assertThrows(IllegalStateException.class, sys::update);
            assertTrue(ex.getMessage().contains("declared as managed object"));
        }
    }

    @Test
    void unmanaged_param_missing_raw_component_is_skipped_gracefully() {
        ComponentManager cm = new ComponentManager();
        try (ArchetypeWorld world = new ArchetypeWorld(cm)) {
            world.registerComponent(URaw.class);
            world.registerComponent(UOther.class);
            // Create entities with only UOther so the query with URaw filter matches none
            for (int i = 0; i < 10; i++) {
                world.createEntity(UOther.class);
            }
            SysUnmanagedParamMissingRawComponent sys = new SysUnmanagedParamMissingRawComponent();
            SystemManager sm = new SystemManager(world);
            sm.registerSystem(sys);
            assertDoesNotThrow(sys::update);
            assertEquals(0, sys.count);
        }
    }
}


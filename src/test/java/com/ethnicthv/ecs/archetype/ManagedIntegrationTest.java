package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.Component;
import com.ethnicthv.ecs.core.components.ComponentManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ManagedIntegrationTest {

    @Component.Managed
    static class PlayerName implements Component {
        final String name;
        PlayerName(String n) { this.name = n; }
    }

    @Test
    void add_get_replace_remove_managed_component() {
        ComponentManager cm = new ComponentManager();
        try (ArchetypeWorld world = new ArchetypeWorld(cm)) {
            world.registerComponent(PlayerName.class);
            int e = world.createEntity();

            // Add managed component instance
            world.addComponent(e, new PlayerName("Ethnic"));
            PlayerName pn = world.getManagedComponent(e, PlayerName.class);
            assertNotNull(pn);
            assertEquals("Ethnic", pn.name);

            // Replace instance
            world.setManagedComponent(e, new PlayerName("Neo"));
            PlayerName pn2 = world.getManagedComponent(e, PlayerName.class);
            assertNotNull(pn2);
            assertEquals("Neo", pn2.name);

            // Remove
            world.removeComponent(e, PlayerName.class);
            assertNull(world.getManagedComponent(e, PlayerName.class));

            // Destroy shouldn't throw
            world.destroyEntity(e);
        }
    }

    @Test
    void move_between_archetypes_preserves_managed_ticket_if_present() {
        ComponentManager cm = new ComponentManager();
        try (ArchetypeWorld world = new ArchetypeWorld(cm)) {
            // Define an unmanaged component too
            @Component.Layout(Component.LayoutType.SEQUENTIAL)
            class Dummy implements Component { @Component.Field int v; }
            world.registerComponent(PlayerName.class);
            world.registerComponent(Dummy.class);

            int e = world.createEntity(Dummy.class);
            world.addComponent(e, new PlayerName("A"));
            PlayerName p = world.getManagedComponent(e, PlayerName.class);
            assertNotNull(p);
            assertEquals("A", p.name);

            // Add another unmanaged component class to force move
            @Component.Layout(Component.LayoutType.SEQUENTIAL)
            class Dummy2 implements Component { @Component.Field int x; }
            world.registerComponent(Dummy2.class);

            // Add Dummy2 -> structural move; ticket should persist
            world.addComponent(e, Dummy2.class, cm.allocate(Dummy2.class, java.lang.foreign.Arena.ofShared()));
            PlayerName p2 = world.getManagedComponent(e, PlayerName.class);
            assertNotNull(p2);
            assertEquals("A", p2.name);

            // Now remove PlayerName -> should become null
            world.removeComponent(e, PlayerName.class);
            assertNull(world.getManagedComponent(e, PlayerName.class));
        }
    }

    @Test
    void interleave_managed_unmanaged_ops_no_ticket_leaks() {
        ComponentManager cm = new ComponentManager();
        try (ArchetypeWorld world = new ArchetypeWorld(cm)) {
            // Define a couple unmanaged components
            @Component.Layout(Component.LayoutType.SEQUENTIAL)
            class U1 implements Component { @Component.Field int a; }
            @Component.Layout(Component.LayoutType.SEQUENTIAL)
            class U2 implements Component { @Component.Field int b; }

            world.registerComponent(PlayerName.class);
            world.registerComponent(U1.class);
            world.registerComponent(U2.class);

            int e = world.createEntity(U1.class);

            // Add managed, then add another unmanaged -> triggers move
            world.addComponent(e, new PlayerName("P0"));
            world.addComponent(e, U2.class, cm.allocate(U2.class, java.lang.foreign.Arena.ofShared()));
            PlayerName p0 = world.getManagedComponent(e, PlayerName.class);
            assertNotNull(p0);
            assertEquals("P0", p0.name);

            // Remove unmanaged U1 -> move
            world.removeComponent(e, U1.class);
            PlayerName pAfterMove = world.getManagedComponent(e, PlayerName.class);
            assertNotNull(pAfterMove);
            assertEquals("P0", pAfterMove.name);

            // Replace managed instance
            world.setManagedComponent(e, new PlayerName("P1"));
            PlayerName p1 = world.getManagedComponent(e, PlayerName.class);
            assertNotNull(p1);
            assertEquals("P1", p1.name);

            // Remove managed
            world.removeComponent(e, PlayerName.class);
            assertNull(world.getManagedComponent(e, PlayerName.class));

            // Add again and then destroy entity -> should release
            world.addComponent(e, new PlayerName("P2"));
            assertNotNull(world.getManagedComponent(e, PlayerName.class));
            world.destroyEntity(e);
            // Entity gone; can't directly check store, but absence via accessor indicates ticket release or entity removal
        }
    }
}

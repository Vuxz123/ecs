package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.components.Component;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Placeholder for future tests once query supports managed accessors directly.
 */
public class ManagedQueryPlaceholderTest {

    @Component.Managed
    static class PlayerProfile implements Component {
        final String id;
        PlayerProfile(String id) { this.id = id; }
    }

    @Disabled("Enable when query supports managed component accessors")
    @Test
    void query_over_managed_components_reads_instances() {
        // TODO: Add a query that selects entities with PlayerProfile and verifies the object values.
    }
}


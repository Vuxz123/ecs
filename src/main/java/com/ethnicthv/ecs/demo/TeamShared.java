package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.Component;

/**
 * Managed shared component used as a grouping key for ChunkGroup.
 */
@Component.Shared
@Component.Managed
public final class TeamShared implements Component {
    public final String team;

    public TeamShared(String team) {
        this.team = team;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TeamShared that)) return false;
        return java.util.Objects.equals(this.team, that.team);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(team);
    }
}


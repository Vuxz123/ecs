package com.ethnicthv.ecs.benchmark;

import com.ethnicthv.ecs.core.components.Component;

@Component.Managed
public class Tag implements Component {
    final String id;

    public Tag(String id) {
        this.id = id;
    }
}

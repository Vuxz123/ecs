package com.ethnicthv.ecs.archetype;

import com.ethnicthv.ecs.core.components.Component;

@Component.Managed
public class Profile implements Component {
    final String name;

    Profile(String n) {
        this.name = n;
    }
}

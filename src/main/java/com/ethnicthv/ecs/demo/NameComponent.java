package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.Component;

@Component.Managed
public class NameComponent implements Component {
    public String name;
}

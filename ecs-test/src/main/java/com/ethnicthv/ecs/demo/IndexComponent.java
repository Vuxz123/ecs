package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.components.Component;

@Component.Layout(Component.LayoutType.SEQUENTIAL)
public class IndexComponent implements  Component {
    @Component.Field
    public int index;

    public IndexComponent(int index) {
        this.index = index;
    }
}

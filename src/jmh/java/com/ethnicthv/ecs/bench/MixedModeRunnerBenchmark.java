package com.ethnicthv.ecs.bench;

import com.ethnicthv.ecs.core.api.archetype.IQuery;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentHandle;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.SystemManager;
import com.ethnicthv.ecs.core.system.annotation.Query;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class MixedModeRunnerBenchmark {

    static class SysSeq { IQuery q; @Query(fieldInject="q", mode=ExecutionMode.SEQUENTIAL, with={Pos.class, Tag.class})
        private void query(@com.ethnicthv.ecs.core.system.annotation.Component(type=Pos.class) ComponentHandle pos,
                           @com.ethnicthv.ecs.core.system.annotation.Component(type=Tag.class) Tag tag) {
            int ix = pos.resolveFieldIndex("x");
            int iy = pos.resolveFieldIndex("y");
            pos.setFloat(ix, pos.getFloat(ix) + 1f);
            pos.setFloat(iy, pos.getFloat(iy) + 1f);
        } }

    static class SysPar { IQuery q; @Query(fieldInject="q", mode=ExecutionMode.PARALLEL, with={Pos.class, Tag.class})
        private void query(@com.ethnicthv.ecs.core.system.annotation.Component(type=Pos.class) ComponentHandle pos,
                           @com.ethnicthv.ecs.core.system.annotation.Component(type=Tag.class) Tag tag) {
            int ix = pos.resolveFieldIndex("x");
            int iy = pos.resolveFieldIndex("y");
            pos.setFloat(ix, pos.getFloat(ix) + 1f);
            pos.setFloat(iy, pos.getFloat(iy) + 1f);
        } }

    ArchetypeWorld world;
    ComponentManager cm;
    SystemManager sm;
    SysSeq seq;
    SysPar par;

    @Param({"1000","10000","50000"})
    public int entities;

    @Setup
    public void setup() {
        cm = new ComponentManager();
        world = new ArchetypeWorld(cm);
        sm = new SystemManager(world);
        world.registerComponent(Pos.class);
        world.registerComponent(Tag.class);
        for (int i = 0; i < entities; i++) {
            int e = world.createEntity(Pos.class, Tag.class);
            world.setManagedComponent(e, new Tag("t"+i));
        }
        seq = new SysSeq(); par = new SysPar();
        sm.registerSystem(seq); sm.registerSystem(par);
    }

    @TearDown
    public void tearDown(){ world.close(); }

    @Benchmark
    public void runSequential() { seq.q.runQuery(); }

    @Benchmark
    public void runParallel() { par.q.runQuery(); }
}


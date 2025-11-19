package com.ethnicthv.ecs.core.system;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;

import java.util.*;

/**
 * Manages ECS Systems and delegates dependency injection to generated code.
 * <p>
 * The SystemManager is responsible for:
 * <ul>
 *   <li>Registering systems into execution groups</li>
 *   <li>Invoking the annotation-processor generated injector for each system</li>
 *   <li>Running systems in a deterministic pipeline via {@link #update(float)}</li>
 * </ul>
 * <p>
 * All legacy reflection/Proxy-based query injection has been removed in favor of
 * the AP-generated injectors (see {@code QueryProcessor}).
 */
public class SystemManager {
    private final ArchetypeWorld world;
    // Systems organized by group instance for the new pipeline API
    private final Map<SystemGroup, List<ISystem>> systemsByGroup = new HashMap<>();
    // Sorted views of groups by update mode
    private final List<SystemGroup> fixedGroups = new ArrayList<>();
    private final List<SystemGroup> variableGroups = new ArrayList<>();

    public SystemManager(ArchetypeWorld world) {
        this.world = world;
        // Pre-register standard groups so they maintain stable ordering
        registerGroupIfAbsent(SystemGroup.INPUT);
        registerGroupIfAbsent(SystemGroup.SIMULATION);
        registerGroupIfAbsent(SystemGroup.PHYSICS);
        registerGroupIfAbsent(SystemGroup.RENDER);
        registerGroupIfAbsent(SystemGroup.CLEANUP);
        rebuildGroupOrdering();
    }

    private void registerGroupIfAbsent(SystemGroup group) {
        systemsByGroup.computeIfAbsent(group, g -> new ArrayList<>());
    }

    private void rebuildGroupOrdering() {
        fixedGroups.clear();
        variableGroups.clear();
        for (SystemGroup group : systemsByGroup.keySet()) {
            if (group.mode() == UpdateMode.FIXED) {
                fixedGroups.add(group);
            } else {
                variableGroups.add(group);
            }
        }
        Collections.sort(fixedGroups);
        Collections.sort(variableGroups);
    }

    private void tryInvokeGeneratedInjector(Object system) {
        Class<?> cls = system.getClass();
        String injectorName1 = cls.getName() + "__QueryInjector";
        String injectorName2 = cls.getPackageName() + "." + cls.getSimpleName() + "__QueryInjector";
        ReflectiveOperationException last = null;
        for (String injectorName : new String[]{ injectorName1, injectorName2 }) {
            try {
                Class<?> inj = Class.forName(injectorName, false, cls.getClassLoader());
                java.lang.reflect.Method m = inj.getMethod("inject", Object.class, com.ethnicthv.ecs.core.archetype.ArchetypeWorld.class);
                m.invoke(null, system, world);
                return; // success
            } catch (ClassNotFoundException e) {
                last = e; // try next variant
            } catch (ReflectiveOperationException e) {
                last = e;
                break; // found class but failed to invoke
            }
        }
        if (!(last instanceof ClassNotFoundException)) {
            throw new IllegalStateException("Failed to invoke generated injector for " + cls.getName(), last);
        }
        // If no injector class is found, we silently skip injection to keep
        // non-annotated systems working.
    }

    /**
     * Register a system into the default {@link SystemGroup#SIMULATION} group.
     */
    public <T extends ISystem> T registerSystem(T system) {
        return registerPipelineSystem(system, SystemGroup.SIMULATION);
    }

    /**
     * Register a system into an explicit execution group for the pipeline API.
     */
    public <T extends ISystem> T registerPipelineSystem(T system, SystemGroup group) {
        if (system == null) {
            throw new IllegalArgumentException("System cannot be null");
        }
        if (group == null) {
            throw new IllegalArgumentException("SystemGroup cannot be null");
        }

        tryInvokeGeneratedInjector(system);
        system.onAwake(world);

        systemsByGroup.computeIfAbsent(group, g -> new ArrayList<>()).add(system);
        rebuildGroupOrdering();
        return system;
    }

    /**
     * Returns a snapshot of all registered systems across all groups (pipeline systems only).
     */
    public List<ISystem> getRegisteredSystems() {
        List<ISystem> all = new ArrayList<>();
        for (List<ISystem> list : systemsByGroup.values()) {
            all.addAll(list);
        }
        return all;
    }

    /**
     * Returns the systems registered under a specific group.
     */
    public List<ISystem> getSystems(SystemGroup group) {
        List<ISystem> list = systemsByGroup.get(group);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    /**
     * Execute all enabled systems in a single group.
     * This is useful for game loops that want fine-grained control, e.g. fixed-step PHYSICS.
     */
    public void updateGroup(SystemGroup group, float deltaTime) {
        List<ISystem> list = systemsByGroup.get(group);
        if (list == null) return;
        for (ISystem sys : list) {
            if (sys.isEnabled()) {
                sys.onUpdate(deltaTime);
            }
        }
    }

    /**
     * Execute all enabled systems in group-priority order (fixed + variable),
     * primarily useful for simple demos.
     */
    public void update(float deltaTime) {
        // First fixed groups in priority order
        for (SystemGroup group : fixedGroups) {
            updateGroup(group, deltaTime);
        }
        // Then variable groups in priority order
        for (SystemGroup group : variableGroups) {
            updateGroup(group, deltaTime);
        }
    }

    /** Package-private accessors used by GameLoop for orchestration. */
    List<SystemGroup> getFixedGroups() {
        return new ArrayList<>(fixedGroups);
    }

    List<SystemGroup> getVariableGroups() {
        return new ArrayList<>(variableGroups);
    }
}
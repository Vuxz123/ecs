package com.ethnicthv.ecs.core.system;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages ECS Systems and delegates dependency injection to generated code.
 * <p>
 * The SystemManager is responsible for:
 * <ul>
 *   <li>Registering systems</li>
 *   <li>Invoking the annotation-processor generated injector for each system</li>
 *   <li>Relying solely on compile-time validated injection logic</li>
 * </ul>
 * <p>
 * All legacy reflection/Proxy-based query injection has been removed in favor of
 * the AP-generated injectors (see {@code QueryProcessor}).
 */
public class SystemManager {
    private final ArchetypeWorld world;
    private final List<Object> registeredSystems;

    public SystemManager(ArchetypeWorld world) {
        this.world = world;
        this.registeredSystems = new ArrayList<>();
    }

    /**
     * Register a system and inject its dependencies via the generated injector.
     * <p>
     * This will attempt to locate a class named
     * {@code <SystemBinaryName>__QueryInjector} or
     * {@code <SystemPackage>.<SimpleName>__QueryInjector} and invoke its static
     * {@code inject(Object, ArchetypeWorld)} method.
     */
    public <T> T registerSystem(T system) {
        if (system == null) {
            throw new IllegalArgumentException("System cannot be null");
        }
        tryInvokeGeneratedInjector(system);
        registeredSystems.add(system);
        return system;
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

    public List<Object> getRegisteredSystems() {
        return new ArrayList<>(registeredSystems);
    }
}

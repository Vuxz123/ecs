package com.ethnicthv.ecs.core.system;

import com.ethnicthv.ecs.core.api.archetype.IQuery;
import com.ethnicthv.ecs.core.api.archetype.IQueryBuilder;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.system.annotation.Query;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages ECS Systems and handles dependency injection of queries.
 * <p>
 * The SystemManager is responsible for:
 * <ul>
 *   <li>Registering systems</li>
 *   <li>Injecting immutable {@link IQuery} instances into {@link Query} annotated fields</li>
 *   <li>Automatically configuring parallel execution based on {@link ExecutionMode}</li>
 * </ul>
 * <p>
 * Injected queries are immutable and thread-safe. They cannot be modified after injection,
 * ensuring that the query configuration defined in annotations remains consistent.
 */
public class SystemManager {
    private final ArchetypeWorld world;
    private final List<Object> registeredSystems;

    public SystemManager(ArchetypeWorld world) {
        this.world = world;
        this.registeredSystems = new ArrayList<>();
    }

    /**
     * Register a system and inject its dependencies.
     * <p>
     * This method will:
     * <ol>
     *   <li>Scan all fields annotated with {@link Query}</li>
     *   <li>Build immutable {@link IQuery} instances based on annotation parameters</li>
     *   <li>Wrap queries in parallel-executing proxies if mode is PARALLEL</li>
     *   <li>Inject the immutable queries into the system's fields</li>
     * </ol>
     * <p>
     * The injected queries are immutable - calling builder methods like {@code with()}
     * or {@code without()} on them will have no effect and may throw exceptions.
     */
    public <T> T registerSystem(T system) {
        if (system == null) {
            throw new IllegalArgumentException("System cannot be null");
        }
        // Prefer generated injector if present
        tryInvokeGeneratedInjector(system);
        // Fallback to legacy reflection-based injection (field-level) if applicable
        injectDependencies(system);
        registeredSystems.add(system);
        return system;
    }

    private void tryInvokeGeneratedInjector(Object system) {
        Class<?> cls = system.getClass();
        String injectorName = cls.getName() + "__QueryInjector";
        try {
            Class<?> inj = Class.forName(injectorName, false, cls.getClassLoader());
            java.lang.reflect.Method m = inj.getMethod("inject", Object.class, com.ethnicthv.ecs.core.archetype.ArchetypeWorld.class);
            m.invoke(null, system, world);
        } catch (ClassNotFoundException e) {
            // No generated injector; ignore
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke generated injector " + injectorName, e);
        }
    }

    public List<Object> getRegisteredSystems() {
        return new ArrayList<>(registeredSystems);
    }

    private void injectDependencies(Object system) {
        Class<?> systemClass = system.getClass();
        for (Field field : systemClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Query.class)) {
                injectQueryField(system, field);
            }
        }
    }

    private void injectQueryField(Object system, Field field) {
        Query queryAnnotation = field.getAnnotation(Query.class);

        // Field must be IQuery (immutable)
        if (!IQuery.class.isAssignableFrom(field.getType())) {
            throw new IllegalArgumentException(
                "Field " + field.getName() + " in " + system.getClass().getName() +
                " annotated with @Query must be of type IQuery (immutable)"
            );
        }

        try {
            IQuery immutableQuery = buildQuery(queryAnnotation);
            IQuery injectedQuery = queryAnnotation.mode() == ExecutionMode.PARALLEL
                ? createParallelProxy(immutableQuery)
                : immutableQuery;

            field.setAccessible(true);
            field.set(system, injectedQuery);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(
                "Failed to inject query into field " + field.getName() +
                " in " + system.getClass().getName(), e
            );
        }
    }

    private IQuery buildQuery(Query annotation) {
        IQueryBuilder builder = world.query();
        for (Class<?> componentClass : annotation.with()) builder.with(componentClass);
        for (Class<?> componentClass : annotation.without()) builder.without(componentClass);
        if (annotation.any().length > 0) builder.any(annotation.any());
        return builder.build();
    }

    private IQuery createParallelProxy(final IQuery baseQuery) {
        return (IQuery) Proxy.newProxyInstance(
            IQuery.class.getClassLoader(),
            new Class<?>[]{ IQuery.class },
            new ParallelQueryInvocationHandler(baseQuery)
        );
    }

    private record ParallelQueryInvocationHandler(IQuery baseQuery) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Compare Method objects directly for speed and safety
            if (method.equals(QueryMethods.FOR_EACH_ENTITY)) {
                return QueryMethods.FOR_EACH_PARALLEL.invoke(baseQuery, args);
            }
            return method.invoke(baseQuery, args);
        }
    }
}

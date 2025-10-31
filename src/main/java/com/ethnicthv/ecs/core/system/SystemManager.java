package com.ethnicthv.ecs.core.system;

import com.ethnicthv.ecs.core.api.archetype.IArchetypeQuery;
import com.ethnicthv.ecs.core.archetype.ArchetypeQuery;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages ECS Systems and handles dependency injection of queries.
 * <p>
 * The SystemManager is responsible for:
 * <ul>
 *   <li>Registering systems</li>
 *   <li>Injecting {@link ArchetypeQuery} instances into {@link Query} annotated fields</li>
 *   <li>Automatically configuring parallel execution based on {@link ExecutionMode}</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>{@code
 * ArchetypeWorld world = new ArchetypeWorld(componentManager);
 * SystemManager systemManager = new SystemManager(world);
 *
 * MovementSystem movementSystem = new MovementSystem();
 * systemManager.registerSystem(movementSystem);
 *
 * // Now movementSystem's @Query fields are injected and ready to use
 * }</pre>
 */
public class SystemManager {
    private final ArchetypeWorld world;
    private final List<Object> registeredSystems;

    /**
     * Create a new SystemManager for the given world.
     *
     * @param world the ECS world that systems will query
     */
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
     *   <li>Create {@link ArchetypeQuery} instances based on annotation parameters</li>
     *   <li>Wrap queries in parallel-executing proxies if mode is PARALLEL</li>
     *   <li>Inject the queries into the system's fields</li>
     * </ol>
     *
     * @param system the system instance to register
     * @param <T> the type of the system
     * @return the registered system (for chaining)
     * @throws IllegalArgumentException if injection fails
     */
    public <T> T registerSystem(T system) {
        if (system == null) {
            throw new IllegalArgumentException("System cannot be null");
        }

        injectDependencies(system);
        registeredSystems.add(system);
        return system;
    }

    /**
     * Get all registered systems.
     *
     * @return list of registered systems
     */
    public List<Object> getRegisteredSystems() {
        return new ArrayList<>(registeredSystems);
    }

    /**
     * Inject dependencies into a system using reflection.
     * <p>
     * This method scans for fields annotated with {@link Query} and injects
     * appropriately configured {@link ArchetypeQuery} instances.
     *
     * @param system the system to inject dependencies into
     */
    private void injectDependencies(Object system) {
        Class<?> systemClass = system.getClass();

        // Scan all declared fields
        for (Field field : systemClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Query.class)) {
                injectQueryField(system, field);
            }
        }
    }

    /**
     * Inject a single query field.
     *
     * @param system the system instance
     * @param field the field to inject
     */
    private void injectQueryField(Object system, Field field) {
        Query queryAnnotation = field.getAnnotation(Query.class);

        // Validate field type
        if (!IArchetypeQuery.class.isAssignableFrom(field.getType())) {
            throw new IllegalArgumentException(
                "Field " + field.getName() + " in " + system.getClass().getName() +
                " annotated with @Query must be of type ArchetypeQuery"
            );
        }

        try {
            // Build base query from annotation parameters
            IArchetypeQuery baseQuery = buildQuery(queryAnnotation);

            // Create proxy if parallel mode is requested
            IArchetypeQuery injectedQuery = queryAnnotation.mode() == ExecutionMode.PARALLEL
                ? createParallelProxy(baseQuery)
                : baseQuery;

            // Inject into field
            field.setAccessible(true);
            field.set(system, injectedQuery);

        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(
                "Failed to inject query into field " + field.getName() +
                " in " + system.getClass().getName(), e
            );
        }
    }

    /**
     * Build an ArchetypeQuery from annotation parameters.
     *
     * @param annotation the @Query annotation
     * @return configured ArchetypeQuery
     */
    private IArchetypeQuery buildQuery(Query annotation) {
        ArchetypeQuery query = world.query();

        // Add WITH components
        for (Class<?> componentClass : annotation.with()) {
            query.with(componentClass);
        }

        // Add WITHOUT components
        for (Class<?> componentClass : annotation.without()) {
            query.without(componentClass);
        }

        // Add ANY components
        if (annotation.any().length > 0) {
            query.any(annotation.any());
        }

        return query;
    }

    /**
     * Create a parallel-executing proxy for an ArchetypeQuery.
     * <p>
     * This method creates an anonymous subclass that overrides {@code forEachEntity}
     * to delegate to {@code forEachParallel}, providing transparent parallel execution.
     *
     * @param baseQuery the base query to wrap
     * @return a proxy that executes in parallel
     */
    private IArchetypeQuery createParallelProxy(final IArchetypeQuery baseQuery) {
        return new IArchetypeQuery() {
            @Override
            public void forEachEntity(EntityConsumer consumer) {
                // Redirect to parallel execution
                baseQuery.forEachParallel(consumer);
            }

            // Delegate all other methods to base query
            public <T> IArchetypeQuery with(Class<T> componentClass) {
                baseQuery.with(componentClass);
                return this;
            }

            @Override
            public <T> IArchetypeQuery without(Class<T> componentClass) {
                baseQuery.without(componentClass);
                return this;
            }

            @Override
            public IArchetypeQuery any(Class<?>... componentClasses) {
                baseQuery.any(componentClasses);
                return this;
            }

            @Override
            public void forEach(ArchetypeConsumer consumer) {
                baseQuery.forEach(consumer);
            }

            @Override
            public void forEachChunk(ChunkConsumer consumer) {
                baseQuery.forEachChunk(consumer);
            }

            @Override
            public int count() {
                return baseQuery.count();
            }

            @Override
            public void forEachParallel(EntityConsumer consumer) {
                baseQuery.forEachParallel(consumer);
            }
        };
    }
}


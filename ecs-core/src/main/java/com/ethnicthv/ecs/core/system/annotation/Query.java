package com.ethnicthv.ecs.core.system.annotation;

import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.SystemManager;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to declare a query field in an ECS System.
 * <p>
 * This annotation is used to mark fields that should be automatically injected
 * with an {@link com.ethnicthv.ecs.core.archetype.ArchetypeQuery} instance by
 * the {@link SystemManager}.
 * <p>
 * Example usage:
 * <pre>{@code
 * public class MovementSystem {
 *     @Query(mode = ExecutionMode.PARALLEL)
 *     private ArchetypeQuery movingEntities;
 *
 *     public void update(float deltaTime) {
 *         movingEntities.forEachEntity((entityId, location, chunk, archetype) -> {
 *             // This will execute in parallel across CPU cores
 *             // Process entity...
 *         });
 *     }
 * }
 * }</pre>
 *
 * @see ExecutionMode
 * @see SystemManager
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Query {
    /**
     * Specifies the execution mode for this query.
     * <p>
     * When set to {@link ExecutionMode#PARALLEL}, calls to {@code forEachEntity}
     * will be automatically routed to {@code forEachParallel} for multi-threaded
     * execution.
     * <p>
     * <strong>Thread Safety Warning:</strong> When using {@code PARALLEL} mode,
     * ensure that all operations in your entity processing logic are thread-safe.
     *
     * @return the execution mode (default: SEQUENTIAL)
     */
    ExecutionMode mode() default ExecutionMode.SEQUENTIAL;

    /**
     * Component classes that entities must have (WITH clause).
     * <p>
     * Entities will only match this query if they have ALL of the specified components.
     *
     * @return array of required component classes (default: empty)
     */
    Class<?>[] with() default {};

    /**
     * Component classes that entities must NOT have (WITHOUT clause).
     * <p>
     * Entities will only match this query if they have NONE of the specified components.
     *
     * @return array of excluded component classes (default: empty)
     */
    Class<?>[] without() default {};

    /**
     * Component classes where entities must have AT LEAST ONE (ANY clause).
     * <p>
     * Entities will only match this query if they have at least one of the specified components.
     *
     * @return array of optional component classes (default: empty)
     */
    Class<?>[] any() default {};

    String fieldInject();
}


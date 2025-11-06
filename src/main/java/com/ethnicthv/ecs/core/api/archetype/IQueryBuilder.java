package com.ethnicthv.ecs.core.api.archetype;

/**
 * Mutable builder interface for constructing archetype queries.
 * <p>
 * This interface follows the Builder pattern and allows fluent configuration
 * of query criteria. Once configuration is complete, call {@link #build()}
 * to create an immutable {@link IQuery} instance.
 * <p>
 * Example usage:
 * <pre>{@code
 * IQuery query = world.query()
 *     .with(Position.class)
 *     .with(Velocity.class)
 *     .without(Stunned.class)
 *     .build();
 * }</pre>
 *
 * @see IQuery
 */
public interface IQueryBuilder {

    /**
     * Add a required component to the query.
     * <p>
     * Entities must have this component to match the query.
     *
     * @param componentClass the component class to require
     * @param <T> the component type
     * @return this builder for chaining
     */
    <T> IQueryBuilder with(Class<T> componentClass);

    /**
     * Add an excluded component to the query.
     * <p>
     * Entities must NOT have this component to match the query.
     *
     * @param componentClass the component class to exclude
     * @param <T> the component type
     * @return this builder for chaining
     */
    <T> IQueryBuilder without(Class<T> componentClass);

    /**
     * Add optional components to the query.
     * <p>
     * Entities must have at least ONE of these components to match the query.
     *
     * @param componentClasses the component classes (at least one required)
     * @return this builder for chaining
     */
    IQueryBuilder any(Class<?>... componentClasses);

    /**
     * Filter by a managed shared component value. Only chunks in groups with this shared value will be considered.
     */
    IQueryBuilder withShared(Object managedValue);

    /**
     * Filter by an unmanaged shared component value (type + 64-bit value).
     */
    IQueryBuilder withShared(Class<?> unmanagedSharedType, long value);

    /**
     * Build an immutable query from this builder's configuration.
     * <p>
     * The returned {@link IQuery} is thread-safe and immutable.
     * This builder can continue to be used after calling build().
     *
     * @return an immutable query instance
     */
    IQuery build();
}

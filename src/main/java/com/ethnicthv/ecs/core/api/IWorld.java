package com.ethnicthv.ecs.core.api;

import com.ethnicthv.ecs.core.api.archetype.IArchetypeQuery;

/**
 * Public API for the ECS world, providing safe access to entity and component management.
 * <p>
 * This interface exposes the core functionality of the Archetype-based ECS system
 * without revealing internal implementation details.
 */
public interface IWorld extends AutoCloseable {
    /**
     * Register a component type in the world.
     *
     * @param componentClass The component class to register.
     * @return The unique type ID assigned to this component.
     */
    <T> int registerComponent(Class<T> componentClass);

    /**
     * Create a new entity with no components.
     *
     * @return The unique ID of the newly created entity.
     */
    int createEntity();

    /**
     * Create a new entity with the specified component types.
     * Components are initialized with default (zeroed) values.
     *
     * @param componentClasses The component classes to add to the entity.
     * @return The unique ID of the newly created entity.
     */
    int createEntity(Class<?>... componentClasses);

    /**
     * Check if an entity has a specific component.
     *
     * @param entityId The entity ID.
     * @param componentClass The component class to check for.
     * @return True if the entity has the component, false otherwise.
     */
    <T> boolean hasComponent(int entityId, Class<T> componentClass);

    /**
     * Destroy an entity and remove it from the world.
     *
     * @param entityId The ID of the entity to destroy.
     */
    void destroyEntity(int entityId);

    /**
     * Create a query for filtering entities based on component requirements.
     *
     * @return A new query instance.
     */
    IArchetypeQuery query();

    /**
     * Get the total number of entities currently in the world.
     *
     * @return The entity count.
     */
    int getEntityCount();

    /**
     * Get the component type ID for a registered component class.
     *
     * @param componentClass The component class.
     * @return The type ID, or null if not registered.
     */
    Integer getComponentTypeId(Class<?> componentClass);

    /**
     * Close the world and release all associated resources.
     */
    @Override
    void close();
}


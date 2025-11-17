package com.ethnicthv.ecs;

import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.components.ComponentManager;
import com.ethnicthv.ecs.core.components.IBindableHandle;
import com.ethnicthv.ecs.core.system.GameLoop;
import com.ethnicthv.ecs.core.system.ISystem;
import com.ethnicthv.ecs.core.system.SystemGroup;
import com.ethnicthv.ecs.core.system.SystemManager;
// Lưu ý: GeneratedComponents có thể chưa tồn tại ở lần build đầu tiên;
// chúng ta sẽ dùng reflection trong Builder.build() thay vì import trực tiếp.

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ECS - The single entry point (Facade) for the Entity Component System.
 * <p>
 * Wraps ComponentManager, ArchetypeWorld, and SystemManager into a unified API.
 * Provides a Builder for fluent initialization and resource management via AutoCloseable.
 */
public final class ECS implements AutoCloseable {

    private final ArchetypeWorld world;
    private final SystemManager systemManager;

    // Private constructor, use ECS.builder() instead.
    private ECS(ArchetypeWorld world, SystemManager systemManager) {
        this.world = world;
        this.systemManager = systemManager;
    }

    /**
     * Create a new Builder instance to configure the ECS world.
     */
    public static Builder builder() {
        return new Builder();
    }

    // =================================================================
    // Public API Delegates
    // =================================================================

    /**
     * Create a new entity with the specified component types.
     * Components will be initialized with default (zero) values.
     *
     * @param components The component classes to add.
     * @return The entity ID.
     */
    public int createEntity(Class<?>... components) {
        // Delegate to the concrete overloads on ArchetypeWorld to avoid signature mismatch
        return switch (components.length) {
            case 0 -> world.createEntity();
            case 1 -> world.createEntity(components[0]);
            case 2 -> world.createEntity(components[0], components[1]);
            case 3 -> world.createEntity(components[0], components[1], components[2]);
            case 4 -> world.createEntity(components[0], components[1], components[2], components[3]);
            case 5 -> world.createEntity(components[0], components[1], components[2], components[3], components[4]);
            case 6 -> world.createEntity(components[0], components[1], components[2], components[3], components[4], components[5]);
            default -> throw new IllegalArgumentException("ArchetypeWorld supports up to 6 component classes in createEntity(..); got " + components.length);
        };
    }

    /**
     * Add a component to an entity and initialize it immediately using a zero-copy lambda.
     * <p>
     * Example:
     * <pre>{@code
     * ecs.addComponent(entityId, PositionComponent.class, (PositionHandle p) -> {
     *     p.setX(100);
     *     p.setY(200);
     * });
     * }</pre>
     *
     * @param entityId       The target entity ID.
     * @param componentClass The component class to add.
     * @param initializer    A lambda to configure the component data directly in the chunk.
     * @param <THandle>      The type of the generated handle.
     */
    public <THandle extends IBindableHandle> void addComponent(
            int entityId,
            Class<?> componentClass,
            Consumer<THandle> initializer
    ) {
        world.addComponent(entityId, componentClass, initializer);
    }

    /**
     * Access the underlying ArchetypeWorld for advanced operations.
     */
    public ArchetypeWorld getWorld() {
        return world;
    }

    /**
     * Access the SystemManager to retrieve registered systems.
     */
    public SystemManager getSystemManager() {
        return systemManager;
    }

    /**
     * Closes the ECS world, releasing all off-heap memory resources (Arenas).
     */
    @Override
    public void close() {
        world.close();
    }

    /**
     * Run a single system group once with the given deltaTime.
     * This is useful for custom loops or tests that want fine-grained control
     * without creating a full {@link GameLoop}.
     */
    public void updateGroup(SystemGroup group, float deltaTime) {
        systemManager.updateGroup(group, deltaTime);
    }

    /**
     * Create a {@link GameLoop} instance bound to this ECS's SystemManager.
     * Caller is responsible for running and stopping the loop.
     */
    public GameLoop createGameLoop(float targetTickRate) {
        return new GameLoop(systemManager, targetTickRate);
    }

    /**
     * Run a simple game loop using a default fixed tick rate of 60 Hz.
     * This call blocks until the current thread is interrupted or the loop is otherwise stopped.
     */
    public void run() {
        run(60.0f);
    }

    /**
     * Run a simple game loop with the specified fixed tick rate (in Hz).
     * Uses {@link GameLoop} internally to coordinate INPUT, SIMULATION, PHYSICS, RENDER and CLEANUP groups.
     */
    public void run(float targetTickRate) {
        GameLoop loop = new GameLoop(systemManager, targetTickRate);
        loop.run();
    }

    // =================================================================
    // Builder Implementation
    // =================================================================

    public static class Builder {
        // We create ComponentManager first because it is the Source of Truth for IDs.
        private final ComponentManager componentManager = new ComponentManager();
        // Store systems with optional explicit group; null group => default SIMULATION
        private final List<SystemEntry> systems = new ArrayList<>();
        private boolean autoRegisterComponents = true;

        // Simple holder for system + optional group selection
        private static final class SystemEntry {
            final ISystem system;
            final SystemGroup group; // may be null => use default

            SystemEntry(ISystem system, SystemGroup group) {
                this.system = system;
                this.group = group;
            }
        }

        /**
         * Disable automatic registration of components found by the Annotation Processor.
         * Use this if you want to manually control component registration order.
         */
        public Builder noAutoRegistration() {
            this.autoRegisterComponents = false;
            return this;
        }

        /**
         * Manually register a component.
         * Useful if auto-registration is disabled or for runtime-defined components.
         */
        public Builder registerComponent(Class<?> componentClass) {
            componentManager.registerComponent(componentClass);
            return this;
        }

        /**
         * Add a System instance to be registered in the world in the default SIMULATION group.
         * The SystemManager will automatically inject queries into this system.
         */
        public Builder addSystem(ISystem system) {
            systems.add(new SystemEntry(system, null));
            return this;
        }

        /**
         * Add a System instance to be registered in a specific execution group.
         */
        public Builder addSystem(ISystem system, SystemGroup group) {
            systems.add(new SystemEntry(system, group));
            return this;
        }

        /**
         * Build and initialize the ECS world.
         * This will:
         * 1. Auto-register components (if enabled).
         * 2. Create the ArchetypeWorld.
         * 3. Create the SystemManager and register all added systems.
         */
        public ECS build() {
            // 1. Auto-register components via generated code
            if (autoRegisterComponents) {
                try {
                    // Use reflection to avoid hard dependency on the generated class at compile time.
                    Class<?> gen = Class.forName("com.ethnicthv.ecs.generated.GeneratedComponents", false, getClass().getClassLoader());
                    java.lang.reflect.Method m = gen.getMethod("registerAll", ComponentManager.class);
                    m.invoke(null, componentManager);
                } catch (ClassNotFoundException e) {
                    // This happens if the user hasn't run the build/AP yet.
                    // We warn them but proceed (manual registration might still work).
                    System.err.println("[ECS] Warning: 'GeneratedComponents' class not found. " +
                            "Automatic component registration failed. " +
                            "Ensure Annotation Processing is enabled and the project is built.");
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Failed to invoke GeneratedComponents.registerAll", e);
                }
            }

            // 2. Create World (ComponentManager passed in as the single source of truth)
            ArchetypeWorld world = new ArchetypeWorld(componentManager);

            // 3. Create SystemManager
            SystemManager sysMgr = new SystemManager(world);

            // 4. Register Systems (Dependency Injection happens here)
            for (SystemEntry entry : systems) {
                if (entry.group == null) {
                    sysMgr.registerSystem(entry.system); // default SIMULATION group
                } else {
                    sysMgr.registerPipelineSystem(entry.system, entry.group);
                }
            }

            return new ECS(world, sysMgr);
        }
    }
}

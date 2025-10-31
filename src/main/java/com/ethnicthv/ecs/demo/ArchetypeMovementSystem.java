package com.ethnicthv.ecs.demo;

import com.ethnicthv.ecs.core.api.archetype.IArchetype;
import com.ethnicthv.ecs.core.api.archetype.IArchetypeChunk;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.archetype.ComponentMask;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Movement system optimized for Archetype-based ECS.
 * Processes entities in cache-friendly chunks.
 */
public final class ArchetypeMovementSystem {
    private final ArchetypeWorld world;
    private final int positionTypeId;
    private final int velocityTypeId;

    public ArchetypeMovementSystem(ArchetypeWorld world) {
        this.world = world;
        this.positionTypeId = world.getComponentTypeId(PositionComponent.class);
        this.velocityTypeId = world.getComponentTypeId(VelocityComponent.class);
    }

    /**
     * Update all entities that have both Position and Velocity
     */
    public void update(float deltaTime) {
        world.query()
            .with(PositionComponent.class)
            .with(VelocityComponent.class)
            .forEachChunk((chunk, archetype) -> {
                updateChunk(chunk, archetype, deltaTime);
            });
    }

    private void updateChunk(IArchetypeChunk chunk, IArchetype archetype, float deltaTime) {
        int size = chunk.size();

        // Get component indices in this archetype
        int posIndex = getComponentIndex(archetype, positionTypeId);
        int velIndex = getComponentIndex(archetype, velocityTypeId);

        // Process each entity in the chunk
        for (int i = 0; i < size; i++) {
            MemorySegment posData = chunk.getComponentData(posIndex, i);
            MemorySegment velData = chunk.getComponentData(velIndex, i);

            if (posData != null && velData != null) {
                // Read velocity
                float vx = velData.get(ValueLayout.JAVA_FLOAT, 0);
                float vy = velData.get(ValueLayout.JAVA_FLOAT, 4);

                // Read position
                float x = posData.get(ValueLayout.JAVA_FLOAT, 0);
                float y = posData.get(ValueLayout.JAVA_FLOAT, 4);

                // Update position
                x += vx * deltaTime;
                y += vy * deltaTime;

                // Write back
                posData.set(ValueLayout.JAVA_FLOAT, 0, x);
                posData.set(ValueLayout.JAVA_FLOAT, 4, y);
            }
        }
    }

    private int getComponentIndex(IArchetype archetype, int componentTypeId) {
        int index = 0;
        ComponentMask mask = archetype.getMask();
        for (int i = 0; i < componentTypeId; i++) {
            if (mask.has(i)) {
                index++;
            }
        }
        return index;
    }
}

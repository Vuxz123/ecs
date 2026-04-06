package com.ethnicthv.ecs.boid.debug;

import com.ethnicthv.ecs.core.archetype.Archetype;
import com.ethnicthv.ecs.core.archetype.ArchetypeChunk;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.archetype.ChunkGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class WorldStatsCollector {
    private static final int OCCUPANCY_BUCKET_COUNT = 10;

    private WorldStatsCollector() {
    }

    public static WorldStatsSnapshot collect(ArchetypeWorld world) {
        int archetypeCount = 0;
        int chunkGroupCount = 0;
        int chunkCount = 0;
        int totalChunkCapacity = 0;
        int totalChunkEntities = 0;
        float[] occupancyHistogram = new float[OCCUPANCY_BUCKET_COUNT];
        List<ArchetypeSummary> archetypes = new ArrayList<>();

        for (Archetype archetype : world.getAllArchetypes()) {
            archetypeCount++;

            int archetypeChunkGroups = 0;
            int archetypeChunkCount = 0;
            int archetypeEntities = archetype.getEntityCount();
            int archetypeCapacity = 0;

            for (ChunkGroup group : archetype.getAllChunkGroups()) {
                archetypeChunkGroups++;
                chunkGroupCount++;
                ArchetypeChunk[] chunks = group.getChunksSnapshot();
                int count = group.chunkCount();
                for (int i = 0; i < count; i++) {
                    ArchetypeChunk chunk = chunks[i];
                    if (chunk == null) {
                        continue;
                    }

                    int chunkEntities = chunk.getEntityCount();
                    int chunkCapacity = chunk.getCapacity();
                    chunkCount++;
                    archetypeChunkCount++;
                    totalChunkCapacity += chunkCapacity;
                    totalChunkEntities += chunkEntities;
                    archetypeCapacity += chunkCapacity;

                    float occupancy = chunkCapacity == 0 ? 0.0f : chunkEntities / (float) chunkCapacity;
                    occupancyHistogram[occupancyBucketIndex(occupancy)]++;
                }
            }

            float occupancy = archetypeCapacity == 0 ? 0.0f : archetypeEntities / (float) archetypeCapacity;
            archetypes.add(new ArchetypeSummary(
                archetypeCount,
                archetype.getComponentTypeIds().length,
                archetypeChunkGroups,
                archetypeChunkCount,
                archetypeEntities,
                occupancy
            ));
        }

        archetypes.sort(Comparator.comparingInt(ArchetypeSummary::entityCount).reversed());
        List<OccupancyBucket> topBuckets = collectTopBuckets(occupancyHistogram);
        return new WorldStatsSnapshot(
            archetypeCount,
            chunkGroupCount,
            chunkCount,
            totalChunkCapacity,
            totalChunkEntities,
            totalChunkCapacity == 0 ? 0.0f : totalChunkEntities / (float) totalChunkCapacity,
            occupancyHistogram,
            archetypes,
            topBuckets
        );
    }

    private static List<OccupancyBucket> collectTopBuckets(float[] occupancyHistogram) {
        List<OccupancyBucket> buckets = new ArrayList<>();
        for (int i = 0; i < occupancyHistogram.length; i++) {
            float count = occupancyHistogram[i];
            if (count <= 0.0f) {
                continue;
            }

            float minFill = i * 10.0f;
            float maxFill = (i + 1) * 10.0f;
            buckets.add(new OccupancyBucket(minFill, maxFill, (int) count));
        }

        buckets.sort(Comparator.comparingInt(OccupancyBucket::chunkCount).reversed());
        if (buckets.size() > 3) {
            return new ArrayList<>(buckets.subList(0, 3));
        }
        return buckets;
    }

    private static int occupancyBucketIndex(float occupancy) {
        int index = (int) Math.floor(occupancy * OCCUPANCY_BUCKET_COUNT);
        if (index < 0) {
            return 0;
        }
        if (index >= OCCUPANCY_BUCKET_COUNT) {
            return OCCUPANCY_BUCKET_COUNT - 1;
        }
        return index;
    }

    public record WorldStatsSnapshot(
        int archetypeCount,
        int chunkGroupCount,
        int chunkCount,
        int totalChunkCapacity,
        int totalChunkEntities,
        float averageChunkOccupancy,
        float[] occupancyHistogram,
        List<ArchetypeSummary> archetypes,
        List<OccupancyBucket> topOccupancyBuckets
    ) {
    }

    public record ArchetypeSummary(
        int ordinal,
        int componentCount,
        int chunkGroupCount,
        int chunkCount,
        int entityCount,
        float occupancy
    ) {
    }

    public record OccupancyBucket(
        float minFillPercent,
        float maxFillPercent,
        int chunkCount
    ) {
    }
}

package com.ethnicthv.ecs.boid.sim;

import java.util.Arrays;

final class SpatialHashGrid {
    private static final float EPSILON = 0.0001f;
    private static final int NEIGHBOR_CELL_COUNT = 27;
    private static final int NEIGHBOR_SLOT_STRIDE = 11;
    private static final int OFFSET_NEGATIVE = 0;
    private static final int OFFSET_ZERO = 1;
    private static final int OFFSET_POSITIVE = 2;

    private final float worldHalfExtent;
    private final float worldSize;
    private final float cellExtent;
    private final int cellsPerAxis;
    private final float neighborRadiusSq;
    private final int maxNeighbors;
    private final int targetSamplesPerCell;
    private final int neighborRefreshIntervalTicks;
    private final float approximateNeighborCenterRejectSq;

    private int[] boundEntityIds = new int[0];
    private int[] entityIdToBoidIndex = new int[0];

    private float[] positionX = new float[0];
    private float[] positionY = new float[0];
    private float[] positionZ = new float[0];
    private float[] velocityX = new float[0];
    private float[] velocityY = new float[0];
    private float[] velocityZ = new float[0];
    private int[] cellKeys = new int[0];
    private int[] orderedBoids = new int[0];
    private float[] orderedPositionX = new float[0];
    private float[] orderedPositionY = new float[0];
    private float[] orderedPositionZ = new float[0];
    private float[] orderedVelocityX = new float[0];
    private float[] orderedVelocityY = new float[0];
    private float[] orderedVelocityZ = new float[0];
    private int[] fixedNeighborCounts = new int[0];
    private int[] fixedNeighbors = new int[0];
    private byte[] fixedNeighborOffsetPacks = new byte[0];
    private int framesSinceNeighborRefresh;
    private boolean neighborBufferInitialized;
    private final int[] cellCounts;
    private final int[] cellStarts;
    private final int[] cellWriteOffsets;
    private final int[] neighborCells;
    private final float[] neighborOffsetX;
    private final float[] neighborOffsetY;
    private final float[] neighborOffsetZ;
    private final byte[] neighborOffsetPacks;
    private final float[] neighborCenterDeltaX;
    private final float[] neighborCenterDeltaY;
    private final float[] neighborCenterDeltaZ;
    private final float[] cellMinX;
    private final float[] cellMinY;
    private final float[] cellMinZ;
    private final float[] cellMaxX;
    private final float[] cellMaxY;
    private final float[] cellMaxZ;

    SpatialHashGrid(SimulationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        this.worldHalfExtent = config.worldHalfExtent();
        this.worldSize = worldHalfExtent * 2.0f;
        this.cellsPerAxis = resolveCellsPerAxis(worldSize, config.neighborRadius());
        this.cellExtent = worldSize / cellsPerAxis;
        this.neighborRadiusSq = config.neighborRadius() * config.neighborRadius();
        this.maxNeighbors = config.maxNeighbors();
        this.targetSamplesPerCell = config.targetSamplesPerCell();
        this.neighborRefreshIntervalTicks = config.neighborRefreshIntervalTicks();
        float approximateRejectRadius = config.neighborRadius() + cellExtent * 0.30f;
        this.approximateNeighborCenterRejectSq = approximateRejectRadius * approximateRejectRadius;
        this.cellCounts = new int[cellsPerAxis * cellsPerAxis * cellsPerAxis];
        this.cellStarts = new int[cellCounts.length];
        this.cellWriteOffsets = new int[cellCounts.length];
        this.neighborCells = new int[cellCounts.length * NEIGHBOR_CELL_COUNT];
        this.neighborOffsetX = new float[cellCounts.length * NEIGHBOR_CELL_COUNT];
        this.neighborOffsetY = new float[cellCounts.length * NEIGHBOR_CELL_COUNT];
        this.neighborOffsetZ = new float[cellCounts.length * NEIGHBOR_CELL_COUNT];
        this.neighborOffsetPacks = new byte[cellCounts.length * NEIGHBOR_CELL_COUNT];
        this.neighborCenterDeltaX = new float[cellCounts.length * NEIGHBOR_CELL_COUNT];
        this.neighborCenterDeltaY = new float[cellCounts.length * NEIGHBOR_CELL_COUNT];
        this.neighborCenterDeltaZ = new float[cellCounts.length * NEIGHBOR_CELL_COUNT];
        this.cellMinX = new float[cellCounts.length];
        this.cellMinY = new float[cellCounts.length];
        this.cellMinZ = new float[cellCounts.length];
        this.cellMaxX = new float[cellCounts.length];
        this.cellMaxY = new float[cellCounts.length];
        this.cellMaxZ = new float[cellCounts.length];
        buildCellBounds();
        buildNeighborLookup();
        ensureBoidCapacity(config.initialBoidCount());
    }

    void reserveBoids(int boidCount) {
        if (boidCount < 0) {
            throw new IllegalArgumentException("boidCount must be >= 0");
        }
        ensureBoidCapacity(boidCount);
    }

    void bindBoids(int[] entityIds) {
        for (int entityId : boundEntityIds) {
            if (entityId >= 0 && entityId < entityIdToBoidIndex.length) {
                entityIdToBoidIndex[entityId] = -1;
            }
        }

        boundEntityIds = Arrays.copyOf(entityIds, entityIds.length);
        ensureBoidCapacity(boundEntityIds.length);

        int maxEntityId = 0;
        for (int entityId : boundEntityIds) {
            if (entityId > maxEntityId) {
                maxEntityId = entityId;
            }
        }
        ensureEntityMappingCapacity(maxEntityId + 1);

        for (int boidIndex = 0; boidIndex < boundEntityIds.length; boidIndex++) {
            entityIdToBoidIndex[boundEntityIds[boidIndex]] = boidIndex;
        }

        framesSinceNeighborRefresh = 0;
        neighborBufferInitialized = false;
    }

    void beginFrame() {
        Arrays.fill(cellCounts, 0);
    }

    int captureBoid(int entityId, float px, float py, float pz, float vx, float vy, float vz) {
        int boidIndex = boidIndexOf(entityId);
        if (boidIndex < 0) {
            throw new IllegalStateException("Unknown boid entity: " + entityId);
        }

        int cellKey = computeCellKey(px, py, pz);
        positionX[boidIndex] = px;
        positionY[boidIndex] = py;
        positionZ[boidIndex] = pz;
        velocityX[boidIndex] = vx;
        velocityY[boidIndex] = vy;
        velocityZ[boidIndex] = vz;
        cellKeys[boidIndex] = cellKey;
        cellCounts[cellKey]++;
        return cellKey;
    }

    void publishRenderPosition(int entityId, float px, float py, float pz) {
        int boidIndex = boidIndexOf(entityId);
        if (boidIndex < 0) {
            throw new IllegalStateException("Unknown boid entity: " + entityId);
        }

        publishRenderPositionByIndex(boidIndex, px, py, pz);
    }

    void publishRenderPositionByIndex(int boidIndex, float px, float py, float pz) {
        if (boidIndex < 0 || boidIndex >= positionX.length) {
            throw new IllegalArgumentException("Invalid boidIndex: " + boidIndex);
        }
        positionX[boidIndex] = px;
        positionY[boidIndex] = py;
        positionZ[boidIndex] = pz;
    }

    int copyRenderPositions(float[] target, int stride) {
        if (stride <= 0) {
            throw new IllegalArgumentException("stride must be > 0");
        }

        int visibleBoids = (boundEntityIds.length + stride - 1) / stride;
        int requiredLength = visibleBoids * 3;
        if (target.length < requiredLength) {
            throw new IllegalArgumentException("target length must be >= " + requiredLength);
        }

        int visibleIndex = 0;
        for (int boidIndex = 0; boidIndex < boundEntityIds.length; boidIndex += stride) {
            int baseIndex = visibleIndex * 3;
            target[baseIndex] = positionX[boidIndex];
            target[baseIndex + 1] = positionY[boidIndex];
            target[baseIndex + 2] = positionZ[boidIndex];
            visibleIndex++;
        }
        return visibleIndex;
    }

    void finishFrame() {
        int runningStart = 0;
        for (int cellKey = 0; cellKey < cellCounts.length; cellKey++) {
            cellStarts[cellKey] = runningStart;
            cellWriteOffsets[cellKey] = runningStart;
            runningStart += cellCounts[cellKey];
        }

        for (int boidIndex = 0; boidIndex < boundEntityIds.length; boidIndex++) {
            int cellKey = cellKeys[boidIndex];
            int orderedIndex = cellWriteOffsets[cellKey]++;
            orderedBoids[orderedIndex] = boidIndex;
            orderedPositionX[orderedIndex] = positionX[boidIndex];
            orderedPositionY[orderedIndex] = positionY[boidIndex];
            orderedPositionZ[orderedIndex] = positionZ[boidIndex];
            orderedVelocityX[orderedIndex] = velocityX[boidIndex];
            orderedVelocityY[orderedIndex] = velocityY[boidIndex];
            orderedVelocityZ[orderedIndex] = velocityZ[boidIndex];
        }
    }

    boolean beginNeighborBuild() {
        if (!shouldRefreshNeighborBuffer()) {
            framesSinceNeighborRefresh++;
            return false;
        }

        Arrays.fill(fixedNeighborCounts, 0, boundEntityIds.length, 0);
        return true;
    }

    void buildNeighborsForEntity(int entityId) {
        int boidIndex = boidIndexOf(entityId);
        if (boidIndex < 0) {
            throw new IllegalStateException("Unknown boid entity during neighbor build: " + entityId);
        }
        buildFixedNeighborsForBoid(boidIndex);
    }

    void endNeighborBuild() {
        framesSinceNeighborRefresh = 0;
        neighborBufferInitialized = true;
    }

    int boidIndexOf(int entityId) {
        if (entityId < 0 || entityId >= entityIdToBoidIndex.length) {
            return -1;
        }
        return entityIdToBoidIndex[entityId];
    }

    int computeCellKey(float x, float y, float z) {
        return flattenCell(toCellCoord(x), toCellCoord(y), toCellCoord(z));
    }

    int flattenWrappedCell(int x, int y, int z) {
        return flattenCell(wrapCellCoord(x), wrapCellCoord(y), wrapCellCoord(z));
    }

    int neighborBase(int cellKey) {
        return cellKey * NEIGHBOR_CELL_COUNT;
    }

    int neighborCellAt(int neighborIndex) {
        return neighborCells[neighborIndex];
    }

    float neighborOffsetXAt(int neighborIndex) {
        return neighborOffsetX[neighborIndex];
    }

    float neighborOffsetYAt(int neighborIndex) {
        return neighborOffsetY[neighborIndex];
    }

    float neighborOffsetZAt(int neighborIndex) {
        return neighborOffsetZ[neighborIndex];
    }

    float neighborCenterDeltaXAt(int neighborIndex) {
        return neighborCenterDeltaX[neighborIndex];
    }

    float neighborCenterDeltaYAt(int neighborIndex) {
        return neighborCenterDeltaY[neighborIndex];
    }

    float neighborCenterDeltaZAt(int neighborIndex) {
        return neighborCenterDeltaZ[neighborIndex];
    }

    float cellExtent() {
        return cellExtent;
    }

    float cellMinX(int cellKey) {
        return cellMinX[cellKey];
    }

    float cellMinY(int cellKey) {
        return cellMinY[cellKey];
    }

    float cellMinZ(int cellKey) {
        return cellMinZ[cellKey];
    }

    float cellMaxX(int cellKey) {
        return cellMaxX[cellKey];
    }

    float cellMaxY(int cellKey) {
        return cellMaxY[cellKey];
    }

    float cellMaxZ(int cellKey) {
        return cellMaxZ[cellKey];
    }

    int cellStart(int cellKey) {
        return cellStarts[cellKey];
    }

    int cellEnd(int cellKey) {
        return cellStarts[cellKey] + cellCounts[cellKey];
    }

    int cellBoidAt(int orderedIndex) {
        return orderedBoids[orderedIndex];
    }

    float orderedPositionXAt(int orderedIndex) {
        return orderedPositionX[orderedIndex];
    }

    float orderedPositionYAt(int orderedIndex) {
        return orderedPositionY[orderedIndex];
    }

    float orderedPositionZAt(int orderedIndex) {
        return orderedPositionZ[orderedIndex];
    }

    float orderedVelocityXAt(int orderedIndex) {
        return orderedVelocityX[orderedIndex];
    }

    float orderedVelocityYAt(int orderedIndex) {
        return orderedVelocityY[orderedIndex];
    }

    float orderedVelocityZAt(int orderedIndex) {
        return orderedVelocityZ[orderedIndex];
    }

    int fixedNeighborCount(int boidIndex) {
        return fixedNeighborCounts[boidIndex];
    }

    int fixedNeighborAt(int boidIndex, int neighborSlot) {
        return fixedNeighbors[boidIndex * maxNeighbors + neighborSlot];
    }

    byte fixedNeighborOffsetPackAt(int boidIndex, int neighborSlot) {
        return fixedNeighborOffsetPacks[boidIndex * maxNeighbors + neighborSlot];
    }

    float offsetXFromPack(byte packedOffset) {
        int packed = packedOffset & 0xFF;
        return decodeAxisShift(packed & 0b11) * worldSize;
    }

    float offsetYFromPack(byte packedOffset) {
        int packed = packedOffset & 0xFF;
        return decodeAxisShift((packed >>> 2) & 0b11) * worldSize;
    }

    float offsetZFromPack(byte packedOffset) {
        int packed = packedOffset & 0xFF;
        return decodeAxisShift((packed >>> 4) & 0b11) * worldSize;
    }

    int cellKey(int boidIndex) {
        return cellKeys[boidIndex];
    }

    int cellX(int cellKey) {
        return cellKey % cellsPerAxis;
    }

    int cellY(int cellKey) {
        return (cellKey / cellsPerAxis) % cellsPerAxis;
    }

    int cellZ(int cellKey) {
        return cellKey / (cellsPerAxis * cellsPerAxis);
    }

    float positionX(int boidIndex) {
        return positionX[boidIndex];
    }

    float positionY(int boidIndex) {
        return positionY[boidIndex];
    }

    float positionZ(int boidIndex) {
        return positionZ[boidIndex];
    }

    float velocityX(int boidIndex) {
        return velocityX[boidIndex];
    }

    float velocityY(int boidIndex) {
        return velocityY[boidIndex];
    }

    float velocityZ(int boidIndex) {
        return velocityZ[boidIndex];
    }

    private int flattenCell(int x, int y, int z) {
        return x + y * cellsPerAxis + z * cellsPerAxis * cellsPerAxis;
    }

    private int wrapCellCoord(int coord) {
        int wrapped = coord % cellsPerAxis;
        return wrapped < 0 ? wrapped + cellsPerAxis : wrapped;
    }

    private int toCellCoord(float value) {
        int cell = (int) Math.floor((value + worldHalfExtent) / cellExtent);
        if (cell < 0) {
            return 0;
        }
        if (cell >= cellsPerAxis) {
            return cellsPerAxis - 1;
        }
        return cell;
    }

    private void ensureBoidCapacity(int capacity) {
        if (positionX.length >= capacity) {
            return;
        }

        positionX = Arrays.copyOf(positionX, capacity);
        positionY = Arrays.copyOf(positionY, capacity);
        positionZ = Arrays.copyOf(positionZ, capacity);
        velocityX = Arrays.copyOf(velocityX, capacity);
        velocityY = Arrays.copyOf(velocityY, capacity);
        velocityZ = Arrays.copyOf(velocityZ, capacity);
        cellKeys = Arrays.copyOf(cellKeys, capacity);
        orderedBoids = Arrays.copyOf(orderedBoids, capacity);
        orderedPositionX = Arrays.copyOf(orderedPositionX, capacity);
        orderedPositionY = Arrays.copyOf(orderedPositionY, capacity);
        orderedPositionZ = Arrays.copyOf(orderedPositionZ, capacity);
        orderedVelocityX = Arrays.copyOf(orderedVelocityX, capacity);
        orderedVelocityY = Arrays.copyOf(orderedVelocityY, capacity);
        orderedVelocityZ = Arrays.copyOf(orderedVelocityZ, capacity);
        fixedNeighborCounts = Arrays.copyOf(fixedNeighborCounts, capacity);
        fixedNeighbors = Arrays.copyOf(fixedNeighbors, capacity * maxNeighbors);
        fixedNeighborOffsetPacks = Arrays.copyOf(fixedNeighborOffsetPacks, capacity * maxNeighbors);
    }

    private void ensureEntityMappingCapacity(int capacity) {
        if (entityIdToBoidIndex.length >= capacity) {
            return;
        }

        int previousLength = entityIdToBoidIndex.length;
        entityIdToBoidIndex = Arrays.copyOf(entityIdToBoidIndex, capacity);
        Arrays.fill(entityIdToBoidIndex, previousLength, capacity, -1);
    }

    private void buildCellBounds() {
        int cellArea = cellsPerAxis * cellsPerAxis;
        for (int cellKey = 0; cellKey < cellCounts.length; cellKey++) {
            int x = cellKey % cellsPerAxis;
            int y = (cellKey / cellsPerAxis) % cellsPerAxis;
            int z = cellKey / cellArea;
            float minX = -worldHalfExtent + x * cellExtent;
            float minY = -worldHalfExtent + y * cellExtent;
            float minZ = -worldHalfExtent + z * cellExtent;
            cellMinX[cellKey] = minX;
            cellMinY[cellKey] = minY;
            cellMinZ[cellKey] = minZ;
            cellMaxX[cellKey] = minX + cellExtent;
            cellMaxY[cellKey] = minY + cellExtent;
            cellMaxZ[cellKey] = minZ + cellExtent;
        }
    }

    private void buildNeighborLookup() {
        int cellArea = cellsPerAxis * cellsPerAxis;
        for (int cellKey = 0; cellKey < cellCounts.length; cellKey++) {
            int cellX = cellKey % cellsPerAxis;
            int cellY = (cellKey / cellsPerAxis) % cellsPerAxis;
            int cellZ = cellKey / cellArea;
            int slot = 0;
            int neighborBase = neighborBase(cellKey);

            for (int dz = -1; dz <= 1; dz++) {
                int rawZ = cellZ + dz;
                int wrappedZ = wrapCellCoord(rawZ);
                float offsetZ = wrapOffset(rawZ);
                for (int dy = -1; dy <= 1; dy++) {
                    int rawY = cellY + dy;
                    int wrappedY = wrapCellCoord(rawY);
                    float offsetY = wrapOffset(rawY);
                    for (int dx = -1; dx <= 1; dx++) {
                        int rawX = cellX + dx;
                        int wrappedX = wrapCellCoord(rawX);
                        int neighborIndex = neighborBase + slot++;

                        neighborCells[neighborIndex] = flattenCell(wrappedX, wrappedY, wrappedZ);
                        neighborOffsetX[neighborIndex] = wrapOffset(rawX);
                        neighborOffsetY[neighborIndex] = offsetY;
                        neighborOffsetZ[neighborIndex] = offsetZ;
                        neighborOffsetPacks[neighborIndex] = packOffset(
                            offsetShift(rawX),
                            offsetShift(rawY),
                            offsetShift(rawZ)
                        );
                        neighborCenterDeltaX[neighborIndex] = (wrappedX - cellX) * cellExtent + neighborOffsetX[neighborIndex];
                        neighborCenterDeltaY[neighborIndex] = (wrappedY - cellY) * cellExtent + neighborOffsetY[neighborIndex];
                        neighborCenterDeltaZ[neighborIndex] = (wrappedZ - cellZ) * cellExtent + neighborOffsetZ[neighborIndex];
                    }
                }
            }
        }
    }

    private float wrapOffset(int coord) {
        if (coord < 0) {
            return -worldSize;
        }
        if (coord >= cellsPerAxis) {
            return worldSize;
        }
        return 0.0f;
    }

    private int offsetShift(int coord) {
        if (coord < 0) {
            return -1;
        }
        if (coord >= cellsPerAxis) {
            return 1;
        }
        return 0;
    }

    private boolean shouldRefreshNeighborBuffer() {
        return !neighborBufferInitialized || framesSinceNeighborRefresh + 1 >= neighborRefreshIntervalTicks;
    }

    private void buildFixedNeighborsForBoid(int boidIndex) {
        float px = positionX[boidIndex];
        float py = positionY[boidIndex];
        float pz = positionZ[boidIndex];
        int cellKey = cellKeys[boidIndex];
        int fixedNeighborBase = boidIndex * maxNeighbors;
        int count = 0;
        int neighborBase = neighborBase(cellKey);
        int neighborSlotStart = Math.floorMod(mix32(boidIndex * 31 + cellKey * 17), NEIGHBOR_CELL_COUNT);

        outer:
        for (int slotVisit = 0; slotVisit < NEIGHBOR_CELL_COUNT; slotVisit++) {
            int slot = Math.floorMod(neighborSlotStart + slotVisit * NEIGHBOR_SLOT_STRIDE, NEIGHBOR_CELL_COUNT);
            int neighborLookupIndex = neighborBase + slot;
            float centerDeltaX = neighborCenterDeltaX[neighborLookupIndex];
            float centerDeltaY = neighborCenterDeltaY[neighborLookupIndex];
            float centerDeltaZ = neighborCenterDeltaZ[neighborLookupIndex];
            float centerDistanceSq = centerDeltaX * centerDeltaX + centerDeltaY * centerDeltaY + centerDeltaZ * centerDeltaZ;
            if (centerDistanceSq > approximateNeighborCenterRejectSq) {
                continue;
            }

            int neighborCell = neighborCells[neighborLookupIndex];
            int cellStart = cellStarts[neighborCell];
            int cellEnd = cellStart + cellCounts[neighborCell];
            int cellPopulation = cellEnd - cellStart;
            if (cellPopulation <= 0) {
                continue;
            }

            float offsetX = neighborOffsetX[neighborLookupIndex];
            float offsetY = neighborOffsetY[neighborLookupIndex];
            float offsetZ = neighborOffsetZ[neighborLookupIndex];
            byte offsetPack = neighborOffsetPacks[neighborLookupIndex];
            int sampleStep = sampleStep(cellPopulation);
            int sampleOffset = sampleOffset(boidIndex, slot, sampleStep, cellPopulation);

            for (int orderedIndex = cellStart + sampleOffset; orderedIndex < cellEnd; orderedIndex += sampleStep) {
                int neighborIndex = orderedBoids[orderedIndex];
                if (neighborIndex == boidIndex) {
                    continue;
                }

                float deltaX = orderedPositionX[orderedIndex] + offsetX - px;
                float deltaY = orderedPositionY[orderedIndex] + offsetY - py;
                float deltaZ = orderedPositionZ[orderedIndex] + offsetZ - pz;
                float distanceSq = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
                if (distanceSq <= EPSILON || distanceSq > neighborRadiusSq) {
                    continue;
                }

                int targetIndex = fixedNeighborBase + count;
                fixedNeighbors[targetIndex] = neighborIndex;
                fixedNeighborOffsetPacks[targetIndex] = offsetPack;
                count++;
                if (count >= maxNeighbors) {
                    break outer;
                }
            }
        }

        fixedNeighborCounts[boidIndex] = count;
    }

    private int sampleStep(int cellPopulation) {
        if (cellPopulation <= targetSamplesPerCell) {
            return 1;
        }
        return Math.max(1, (cellPopulation + targetSamplesPerCell - 1) / targetSamplesPerCell);
    }

    private static int sampleOffset(int boidIndex, int slot, int sampleStep, int cellPopulation) {
        if (sampleStep <= 1 || cellPopulation <= 1) {
            return 0;
        }
        int sampleLanes = Math.min(sampleStep, cellPopulation);
        return Math.floorMod(mix32(boidIndex * 17 + slot * 13), sampleLanes);
    }

    private static int mix32(int value) {
        int mixed = value;
        mixed ^= mixed >>> 16;
        mixed *= 0x7feb352d;
        mixed ^= mixed >>> 15;
        mixed *= 0x846ca68b;
        mixed ^= mixed >>> 16;
        return mixed;
    }

    private static byte packOffset(int shiftX, int shiftY, int shiftZ) {
        return (byte) (encodeAxisShift(shiftX) | (encodeAxisShift(shiftY) << 2) | (encodeAxisShift(shiftZ) << 4));
    }

    private static int encodeAxisShift(int shift) {
        return switch (shift) {
            case -1 -> OFFSET_NEGATIVE;
            case 0 -> OFFSET_ZERO;
            case 1 -> OFFSET_POSITIVE;
            default -> throw new IllegalArgumentException("Unsupported offset shift: " + shift);
        };
    }

    private static float decodeAxisShift(int encodedShift) {
        return switch (encodedShift) {
            case OFFSET_NEGATIVE -> -1.0f;
            case OFFSET_ZERO -> 0.0f;
            case OFFSET_POSITIVE -> 1.0f;
            default -> throw new IllegalArgumentException("Unsupported encoded shift: " + encodedShift);
        };
    }

    private static int resolveCellsPerAxis(float worldSize, float requestedCellSize) {
        int floorCandidate = Math.max(1, (int) Math.floor(worldSize / requestedCellSize));
        int ceilCandidate = Math.max(1, (int) Math.ceil(worldSize / requestedCellSize));
        float floorExtent = worldSize / floorCandidate;
        float ceilExtent = worldSize / ceilCandidate;
        float floorError = Math.abs(floorExtent - requestedCellSize);
        float ceilError = Math.abs(ceilExtent - requestedCellSize);
        return floorError <= ceilError ? floorCandidate : ceilCandidate;
    }
}

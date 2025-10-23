package com.ethnicthv.ecs;

import com.ethnicthv.ecs.archetype.ComponentMask;

/**
 * Benchmark comparing ComponentMask (BitSet) vs int[] for component lookups
 */
public class MaskVsArrayBenchmark {

    public static void main(String[] args) {
        System.out.println("=== Benchmark: ComponentMask vs int[] ===\n");

        // Test with different component counts
        int[] componentCounts = {3, 5, 10, 20};

        for (int count : componentCounts) {
            System.out.println("--- Testing with " + count + " components ---");
            benchmarkComponentLookup(count);
            System.out.println();
        }
    }

    private static void benchmarkComponentLookup(int componentCount) {
        // Setup: Create mask and array with same component IDs
        ComponentMask mask = new ComponentMask();
        int[] componentIds = new int[componentCount];

        for (int i = 0; i < componentCount; i++) {
            int id = i * 2; // Sparse IDs: 0, 2, 4, 6, ...
            componentIds[i] = id;
            mask = mask.set(id);
        }

        int iterations = 10_000_000;
        int lookupId = componentCount - 1; // Look for last component

        // Warm up
        for (int i = 0; i < 1000; i++) {
            mask.has(lookupId);
            findInArray(componentIds, lookupId);
        }

        // Benchmark 1: BitSet lookup (mask.has())
        long start1 = System.nanoTime();
        boolean resultMask = false;
        for (int i = 0; i < iterations; i++) {
            resultMask = mask.has(lookupId);
        }
        long time1 = System.nanoTime() - start1;

        // Benchmark 2: Array linear search
        long start2 = System.nanoTime();
        boolean resultArray = false;
        for (int i = 0; i < iterations; i++) {
            resultArray = findInArray(componentIds, lookupId) >= 0;
        }
        long time2 = System.nanoTime() - start2;

        // Benchmark 3: Check if mask contains all from another mask
        ComponentMask queryMask = new ComponentMask().set(0).set(lookupId);
        long start3 = System.nanoTime();
        boolean resultContains = false;
        for (int i = 0; i < iterations; i++) {
            resultContains = mask.contains(queryMask);
        }
        long time3 = System.nanoTime() - start3;

        // Benchmark 4: Check if array contains multiple IDs
        int[] queryIds = {0, lookupId};
        long start4 = System.nanoTime();
        boolean resultArrayMulti = false;
        for (int i = 0; i < iterations; i++) {
            resultArrayMulti = arrayContainsAll(componentIds, queryIds);
        }
        long time4 = System.nanoTime() - start4;

        System.out.printf("  Single lookup:\n");
        System.out.printf("    BitSet.has():        %6.2f ns/op  (result=%b)\n",
            time1 / (double) iterations, resultMask);
        System.out.printf("    Array search:        %6.2f ns/op  (result=%b)\n",
            time2 / (double) iterations, resultArray);
        System.out.printf("    Speedup: %.2fx %s\n",
            Math.max(time1, time2) / (double) Math.min(time1, time2),
            time1 < time2 ? "(BitSet wins)" : "(Array wins)");

        System.out.printf("\n  Multi-component check:\n");
        System.out.printf("    Mask.containsAll():  %6.2f ns/op  (result=%b)\n",
            time3 / (double) iterations, resultContains);
        System.out.printf("    Array containsAll(): %6.2f ns/op  (result=%b)\n",
            time4 / (double) iterations, resultArrayMulti);
        System.out.printf("    Speedup: %.2fx %s\n",
            Math.max(time3, time4) / (double) Math.min(time3, time4),
            time3 < time4 ? "(Mask wins)" : "(Array wins)");

        // Memory comparison
        long maskMemory = estimateMaskMemory(mask);
        long arrayMemory = 16 + componentIds.length * 4; // Array object header + int elements
        System.out.printf("\n  Memory usage:\n");
        System.out.printf("    BitSet (mask):       ~%d bytes\n", maskMemory);
        System.out.printf("    int[] array:         ~%d bytes\n", arrayMemory);
        System.out.printf("    Difference:          %+d bytes (%.1fx)\n",
            maskMemory - arrayMemory,
            maskMemory / (double) arrayMemory);
    }

    private static int findInArray(int[] array, int value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static boolean arrayContainsAll(int[] haystack, int[] needles) {
        for (int needle : needles) {
            boolean found = false;
            for (int hay : haystack) {
                if (hay == needle) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static long estimateMaskMemory(ComponentMask mask) {
        // BitSet internal: long[] words + object overhead
        // Rough estimate: 24 bytes (object) + 16 bytes (array) + words * 8
        // For small IDs, typically 1-2 long words
        return 24 + 16 + 2 * 8; // ~56 bytes minimum
    }
}

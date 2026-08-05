package com.sumirelabs.lightbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;

class CommandLightbenchTest {

    @Test
    void generationPlanUsesThirtySixDisjointSeventeenBySeventeenRegions() {
        final List<List<ChunkPos>> regions = CommandLightbench.createGenerationTestRegions();
        final Set<Long> seen = new HashSet<>();
        int chunkCount = 0;
        int batchCount = 0;

        assertEquals(36, regions.size());
        for (int regionIndex = 0; regionIndex < regions.size(); ++regionIndex) {
            final List<ChunkPos> region = regions.get(regionIndex);
            final int center = 10000 + regionIndex * 40;
            assertEquals(289, region.size());
            assertEquals(center, region.get(0).x);
            assertEquals(center, region.get(0).z);

            for (final ChunkPos chunk : region) {
                assertTrue(Math.abs(chunk.x - center) <= 8);
                assertTrue(Math.abs(chunk.z - center) <= 8);
                assertTrue(seen.add(key(chunk)), "duplicate chunk " + chunk.x + "," + chunk.z);
            }
            chunkCount += region.size();
            batchCount += (region.size() + 4) / 5;
        }

        assertEquals(10404, chunkCount);
        assertEquals(10404, seen.size());
        assertEquals(2088, batchCount);
    }

    @Test
    void warmupSquareContainsExactlyOneHundredAndOneByOneHundredAndOneChunks() {
        final List<ChunkPos> chunks = CommandLightbench.createSquareRegion(-10000, -10000, 50);
        final Set<Long> seen = new HashSet<>();

        assertEquals(10201, chunks.size());
        for (final ChunkPos chunk : chunks) {
            assertTrue(chunk.x >= -10050 && chunk.x <= -9950);
            assertTrue(chunk.z >= -10050 && chunk.z <= -9950);
            assertTrue(seen.add(key(chunk)), "duplicate chunk " + chunk.x + "," + chunk.z);
        }
        assertEquals(10201, seen.size());
    }

    @Test
    void measuredRegionsUseTheOriginalInsertionOrderedRingTraversal() {
        final List<ChunkPos> chunks = CommandLightbench.createSpiralRegion(0, 0, 1);
        final int[][] expected = {{0, 0}, {-1, 1}, {0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}};

        assertEquals(expected.length, chunks.size());
        for (int index = 0; index < expected.length; ++index) {
            assertEquals(expected[index][0], chunks.get(index).x);
            assertEquals(expected[index][1], chunks.get(index).z);
        }
    }

    @Test
    void percentilesUseNearestRankWithoutSkippingAnIndex() {
        final long[] sorted = {10, 20, 30, 40};

        assertEquals(10, CommandLightbench.percentile(sorted, 0.01));
        assertEquals(20, CommandLightbench.percentile(sorted, 0.50));
        assertEquals(40, CommandLightbench.percentile(sorted, 0.99));
    }

    private static long key(final ChunkPos chunk) {
        return ((long) chunk.x << 32) ^ (chunk.z & 0xffffffffL);
    }
}

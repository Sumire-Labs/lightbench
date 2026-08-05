package com.sumirelabs.lightbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
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
    void freshWorldFootprintIncludesOneChunkBorderAroundEveryRegion() {
        final List<List<ChunkPos>> regions = new ArrayList<>();
        regions.add(CommandLightbench.createSquareRegion(-10000, -10000, 50));
        regions.addAll(CommandLightbench.createGenerationTestRegions());

        final List<ChunkPos> footprint = CommandLightbench.createFreshnessFootprint(regions, 1);
        final Set<Long> seen = new HashSet<>();
        for (final ChunkPos chunk : footprint) {
            assertTrue(seen.add(key(chunk)), "duplicate footprint chunk " + chunk.x + "," + chunk.z);
        }

        assertEquals(23605, footprint.size());
        assertTrue(seen.contains(key(new ChunkPos(-10051, -10051))));
        assertTrue(seen.contains(key(new ChunkPos(11409, 11409))));
    }

    @Test
    void generatedChunkProbeStopsAtFirstExistingChunkWithoutRequestingOne() {
        final List<ChunkPos> footprint = Arrays.asList(new ChunkPos(1, 2), new ChunkPos(3, 4), new ChunkPos(5, 6));
        final RecordingChunkProvider provider = new RecordingChunkProvider(new ChunkPos(3, 4));

        assertEquals(new ChunkPos(3, 4), CommandLightbench.findFirstGeneratedChunk(provider, footprint));
        assertEquals(2, provider.checks);

        final RecordingChunkProvider freshProvider = new RecordingChunkProvider();
        assertNull(CommandLightbench.findFirstGeneratedChunk(freshProvider, footprint));
        assertEquals(3, freshProvider.checks);
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

    private static final class RecordingChunkProvider implements IChunkProvider {

        private final Set<Long> generated = new HashSet<>();
        private int checks;

        private RecordingChunkProvider(final ChunkPos... generated) {
            for (final ChunkPos chunk : generated) {
                this.generated.add(key(chunk));
            }
        }

        @Override
        public Chunk getLoadedChunk(final int x, final int z) {
            return null;
        }

        @Override
        public Chunk provideChunk(final int x, final int z) {
            throw new AssertionError("freshness checks must not request chunks");
        }

        @Override
        public boolean tick() {
            return false;
        }

        @Override
        public String makeString() {
            return "test";
        }

        @Override
        public boolean isChunkGeneratedAt(final int x, final int z) {
            ++this.checks;
            return this.generated.contains(key(new ChunkPos(x, z)));
        }
    }
}

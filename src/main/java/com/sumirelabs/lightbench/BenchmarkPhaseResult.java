package com.sumirelabs.lightbench;

/** Raw, integer-nanosecond observations from one completed generation benchmark phase. */
final class BenchmarkPhaseResult {

    final String name;
    final int chunkCount;
    final int batchLimit;
    final long provideNanos;
    final long barrierNanos;
    final long totalNanos;
    final long workerCpuNanos;
    final BatchSamples batches;
    final RegionSamples regions;

    BenchmarkPhaseResult(
            final String name,
            final int chunkCount,
            final int batchLimit,
            final long provideNanos,
            final long barrierNanos,
            final long totalNanos,
            final long workerCpuNanos,
            final BatchSamples batches,
            final RegionSamples regions) {
        this.name = name;
        this.chunkCount = chunkCount;
        this.batchLimit = batchLimit;
        this.provideNanos = provideNanos;
        this.barrierNanos = barrierNanos;
        this.totalNanos = totalNanos;
        this.workerCpuNanos = workerCpuNanos;
        this.batches = batches;
        this.regions = regions;
    }

    static final class BatchSamples {

        final int[] regionIndices;
        final int[] firstIndicesInRegion;
        final int[] chunkCounts;
        final int[] firstChunkX;
        final int[] firstChunkZ;
        final int[] lastChunkX;
        final int[] lastChunkZ;
        final long[] provideNanos;
        final long[] barrierNanos;
        final long[] wallNanos;

        BatchSamples(
                final int[] regionIndices,
                final int[] firstIndicesInRegion,
                final int[] chunkCounts,
                final int[] firstChunkX,
                final int[] firstChunkZ,
                final int[] lastChunkX,
                final int[] lastChunkZ,
                final long[] provideNanos,
                final long[] barrierNanos,
                final long[] wallNanos) {
            final int size = wallNanos.length;
            if (regionIndices.length != size
                    || firstIndicesInRegion.length != size
                    || chunkCounts.length != size
                    || firstChunkX.length != size
                    || firstChunkZ.length != size
                    || lastChunkX.length != size
                    || lastChunkZ.length != size
                    || provideNanos.length != size
                    || barrierNanos.length != size) {
                throw new IllegalArgumentException("batch sample arrays must have equal lengths");
            }
            this.regionIndices = regionIndices;
            this.firstIndicesInRegion = firstIndicesInRegion;
            this.chunkCounts = chunkCounts;
            this.firstChunkX = firstChunkX;
            this.firstChunkZ = firstChunkZ;
            this.lastChunkX = lastChunkX;
            this.lastChunkZ = lastChunkZ;
            this.provideNanos = provideNanos;
            this.barrierNanos = barrierNanos;
            this.wallNanos = wallNanos;
        }

        int size() {
            return this.wallNanos.length;
        }
    }

    static final class RegionSamples {

        final int[] chunkCounts;
        final int[] batchCounts;
        final long[] wallNanos;

        RegionSamples(final int[] chunkCounts, final int[] batchCounts, final long[] wallNanos) {
            if (chunkCounts.length != wallNanos.length || batchCounts.length != wallNanos.length) {
                throw new IllegalArgumentException("region sample arrays must have equal lengths");
            }
            this.chunkCounts = chunkCounts;
            this.batchCounts = batchCounts;
            this.wallNanos = wallNanos;
        }

        int size() {
            return this.wallNanos.length;
        }
    }
}

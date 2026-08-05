package com.sumirelabs.lightbench;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Deterministic generation and lighting benchmarks.
 *
 * <p>{@code /lightbench gen} follows the original lightbench shape: it warms
 * up on a 101x101 region, then measures 36 separate 17x17 regions. Work is
 * submitted five chunks at a time and every batch waits until lighting has
 * fully converged. Coordinates are chunk coordinates, so +/-10000 means
 * roughly +/-160000 blocks from spawn.
 *
 * <p>{@code /lightbench bulk} retains this port's previous contiguous-square
 * throughput test. It generates the whole square before performing one final
 * light barrier, so its result answers a different question from {@code gen}.
 */
public class CommandLightbench extends CommandBase {

    private static final int GEN_WARMUP_CENTER = -10000;
    private static final int GEN_TEST_CENTER = 10000;
    private static final int GEN_WARMUP_RADIUS = 50;
    private static final int GEN_REGION_RADIUS = 8;
    private static final int GEN_BATCH_SIZE = 5;
    private static final int GEN_REGION_COUNT = 36;
    private static final int GEN_REGION_STRIDE = 40;
    private static final int FRESHNESS_HALO = 1;

    private static final int BULK_WARMUP_CENTER = -625;
    private static final int BULK_TEST_CENTER = 625;
    private static final int EDITS_CENTER_X = 20000;
    private static final int EDITS_CENTER_Z = 20000;

    @Override
    public String getName() {
        return "lightbench";
    }

    @Override
    public String getUsage(final ICommandSender sender) {
        return "/lightbench gen | /lightbench bulk [radius] [warmupRadius]"
                + " | /lightbench updates"
                + " | /lightbench edits [size] [reps]"
                + " | /lightbench tps <editsPerTick> [seconds] | /lightbench tps sweep [seconds]"
                + " | /lightbench spikes [editsPerSec] [seconds]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(final MinecraftServer server, final ICommandSender sender, final String[] args)
            throws CommandException {
        final World world = sender.getEntityWorld();
        final LightProbe probe = LightProbe.create(world);

        try {
            if (args.length > 0 && "updates".equalsIgnoreCase(args[0])) {
                if (args.length > 1) {
                    throw new WrongUsageException(getUsage(sender));
                }
                if (TpsTest.isRunning() || SpikeTest.isRunning()) {
                    say(sender, "a tick-measured run is already in progress");
                    return;
                }
                say(
                        sender,
                        "engine: " + probe.engine().name().toLowerCase(Locale.ROOT)
                                + " | mode: updates | 20 warmup + 200 measured pairs per workload");
                UpdateBenchmark.run(sender, world, probe);
                return;
            }

            if (args.length > 0 && "spikes".equals(args[0])) {
                if (TpsTest.isRunning() || SpikeTest.isRunning()) {
                    say(sender, "a tick-measured run is already in progress");
                    return;
                }
                final int size = 64;
                final int editsPerSec = args.length > 1 ? parseInt(args[1], 1, 20) : 2;
                final int seconds = args.length > 2 ? parseInt(args[2], 10, 600) : 60;
                SpikeTest.start(
                        sender,
                        world,
                        editsPerSec,
                        seconds,
                        EDITS_CENTER_X - size / 2,
                        EDITS_CENTER_Z - size / 2,
                        size);
                return;
            }

            if (args.length > 0 && "tps".equals(args[0])) {
                if (TpsTest.isRunning() || SpikeTest.isRunning()) {
                    say(sender, "a tick-measured run is already in progress");
                    return;
                }
                final int size = 64;
                final int baseX = EDITS_CENTER_X - size / 2;
                final int baseZ = EDITS_CENTER_Z - size / 2;
                if (args.length > 1 && "sweep".equals(args[1])) {
                    final int seconds = args.length > 2 ? parseInt(args[2], 5, 120) : 20;
                    TpsTest.startSweep(
                            sender, world, seconds, new int[] {64, 128, 256, 512, 1024, 2048}, baseX, baseZ, size);
                } else {
                    final int editsPerTick = args.length > 1 ? parseInt(args[1], 1, 100000) : 256;
                    final int seconds = args.length > 2 ? parseInt(args[2], 5, 600) : 20;
                    TpsTest.start(sender, world, editsPerTick, seconds, baseX, baseZ, size);
                }
                return;
            }

            if (args.length > 0 && "edits".equals(args[0])) {
                final int size = args.length > 1 ? parseInt(args[1], 8, 128) : 64;
                final int reps = args.length > 2 ? parseInt(args[2], 1, 500) : 50;
                say(
                        sender,
                        "engine: " + probe.engine().name().toLowerCase(Locale.ROOT) + " | edits test: platform " + size
                                + "x" + size + " at y=254, " + reps + " reps");
                runEdits(sender, world, probe, size, reps);
                return;
            }

            if (args.length == 0 || "gen".equalsIgnoreCase(args[0])) {
                if (args.length > 1) {
                    throw new WrongUsageException(getUsage(sender));
                }
                runGenerationBenchmark(sender, world, probe);
                return;
            }

            if ("bulk".equalsIgnoreCase(args[0])) {
                final int radius = args.length > 1 ? parseInt(args[1], 1, 200) : 50;
                final int warmupRadius = args.length > 2 ? parseInt(args[2], 0, 200) : 10;
                if (args.length > 3) {
                    throw new WrongUsageException(getUsage(sender));
                }
                runBulkBenchmark(sender, world, probe, radius, warmupRadius);
                return;
            }

            // Keep the old numeric form usable for existing scripts, while
            // making the distinct benchmark mode explicit in new output.
            if (isUnsignedInteger(args[0])) {
                final int radius = parseInt(args[0], 1, 200);
                final int warmupRadius = args.length > 1 ? parseInt(args[1], 0, 200) : 10;
                if (args.length > 2) {
                    throw new WrongUsageException(getUsage(sender));
                }
                say(
                        sender,
                        "numeric syntax is the legacy bulk mode; prefer /lightbench bulk " + radius + " "
                                + warmupRadius);
                runBulkBenchmark(sender, world, probe, radius, warmupRadius);
                return;
            }

            throw new WrongUsageException(getUsage(sender));
        } catch (final CommandException e) {
            throw e;
        } catch (final Exception e) {
            Lightbench.LOGGER.error("lightbench failed", e);
            throw new CommandException("lightbench failed: " + e);
        }
    }

    private void runGenerationBenchmark(final ICommandSender sender, final World world, final LightProbe probe)
            throws Exception {
        final IChunkProvider provider = world.getChunkProvider();
        final int warmupChunks = squareChunkCount(GEN_WARMUP_RADIUS);
        final int chunksPerRegion = squareChunkCount(GEN_REGION_RADIUS);
        final int measuredChunks = chunksPerRegion * GEN_REGION_COUNT;
        final List<List<ChunkPos>> warmupRegions =
                Collections.singletonList(createSquareRegion(GEN_WARMUP_CENTER, GEN_WARMUP_CENTER, GEN_WARMUP_RADIUS));
        final List<List<ChunkPos>> measuredRegions = createGenerationTestRegions();
        final List<List<ChunkPos>> allRegions = new ArrayList<>(1 + measuredRegions.size());
        allRegions.addAll(warmupRegions);
        allRegions.addAll(measuredRegions);

        say(
                sender,
                "engine: " + probe.engine().name().toLowerCase(Locale.ROOT)
                        + " | seed: " + world.getSeed()
                        + " | mode: gen (batch " + GEN_BATCH_SIZE + ", wait after every batch)");
        say(
                sender,
                "gen plan: warmup " + warmupChunks + " chunks at -10000; test " + GEN_REGION_COUNT + " x "
                        + chunksPerRegion + " = " + measuredChunks + " chunks from +10000");
        final PreflightResult preflight = requireFreshFootprint(sender, provider, allRegions);
        if (!preflight.fresh) {
            return;
        }

        final String startedAtUtc = BenchmarkReport.nowUtc();
        final List<BenchmarkPhaseResult> phases = new ArrayList<>(2);
        phases.add(runBatchedRegions(sender, world, probe, "gen warmup", warmupRegions, GEN_BATCH_SIZE));
        queueUnloadRegions(provider, warmupRegions);

        phases.add(runBatchedRegions(sender, world, probe, "gen test", measuredRegions, GEN_BATCH_SIZE));
        queueUnloadRegions(provider, measuredRegions);
        writeBenchmarkReport(
                sender,
                world,
                probe,
                "gen",
                startedAtUtc,
                preflight,
                BenchmarkReport.generationPlan(
                        GEN_WARMUP_CENTER,
                        GEN_WARMUP_RADIUS,
                        GEN_TEST_CENTER,
                        GEN_REGION_RADIUS,
                        GEN_REGION_COUNT,
                        GEN_REGION_STRIDE,
                        GEN_BATCH_SIZE),
                phases);
    }

    private void runBulkBenchmark(
            final ICommandSender sender,
            final World world,
            final LightProbe probe,
            final int radius,
            final int warmupRadius)
            throws Exception {
        final IChunkProvider provider = world.getChunkProvider();
        final List<List<ChunkPos>> regions = new ArrayList<>(2);
        if (warmupRadius > 0) {
            regions.add(createSquareRegion(BULK_WARMUP_CENTER, BULK_WARMUP_CENTER, warmupRadius));
        }
        regions.add(createSquareRegion(BULK_TEST_CENTER, BULK_TEST_CENTER, radius));

        say(
                sender,
                "engine: " + probe.engine().name().toLowerCase(Locale.ROOT)
                        + " | seed: " + world.getSeed()
                        + " | mode: bulk | radius " + radius + " (warmup " + warmupRadius + ")");
        say(
                sender,
                "bulk submits the complete square before one final light barrier; do not compare it as gen latency");
        final PreflightResult preflight = requireFreshFootprint(sender, provider, regions);
        if (!preflight.fresh) {
            return;
        }
        final String startedAtUtc = BenchmarkReport.nowUtc();
        final List<BenchmarkPhaseResult> phases = new ArrayList<>(2);
        if (warmupRadius > 0) {
            phases.add(runBulkPhase(
                    sender, world, probe, "bulk warmup", BULK_WARMUP_CENTER, BULK_WARMUP_CENTER, warmupRadius));
        }
        phases.add(runBulkPhase(sender, world, probe, "bulk test", BULK_TEST_CENTER, BULK_TEST_CENTER, radius));
        writeBenchmarkReport(
                sender,
                world,
                probe,
                "bulk",
                startedAtUtc,
                preflight,
                BenchmarkReport.bulkPlan(BULK_WARMUP_CENTER, warmupRadius, BULK_TEST_CENTER, radius),
                phases);
    }

    private PreflightResult requireFreshFootprint(
            final ICommandSender sender, final IChunkProvider provider, final List<List<ChunkPos>> regions) {
        final List<ChunkPos> footprint = createFreshnessFootprint(regions, FRESHNESS_HALO);
        final long start = System.nanoTime();
        final ChunkPos existing = findFirstGeneratedChunk(provider, footprint);
        final long elapsed = System.nanoTime() - start;
        if (existing != null) {
            say(
                    sender,
                    "preflight failed: chunk " + existing
                            + " in the benchmark footprint already exists (the check includes a one-chunk border)");
            say(sender, "no benchmark chunks were requested; create a fresh world before measuring generation");
            return new PreflightResult(false, footprint.size(), elapsed);
        }
        say(
                sender,
                String.format(
                        Locale.ROOT,
                        "preflight: %d target-and-border chunks are ungenerated (%.3fs)",
                        footprint.size(),
                        elapsed * 1.0e-9));
        return new PreflightResult(true, footprint.size(), elapsed);
    }

    private void writeBenchmarkReport(
            final ICommandSender sender,
            final World world,
            final LightProbe probe,
            final String mode,
            final String startedAtUtc,
            final PreflightResult preflight,
            final BenchmarkReport.Plan plan,
            final List<BenchmarkPhaseResult> phases) {
        try {
            final Path output = BenchmarkReport.write(
                    world,
                    mode,
                    probe.engine(),
                    startedAtUtc,
                    FRESHNESS_HALO,
                    preflight.footprintChunks,
                    preflight.elapsedNanos,
                    plan,
                    phases);
            say(sender, "raw result saved: " + output.toAbsolutePath());
        } catch (final Exception e) {
            Lightbench.LOGGER.error("could not write Lightbench raw result", e);
            say(sender, "raw result write failed; the completed console measurements remain available in the log");
        }
    }

    private BenchmarkPhaseResult runBatchedRegions(
            final ICommandSender sender,
            final World world,
            final LightProbe probe,
            final String label,
            final List<List<ChunkPos>> regions,
            final int batchSize)
            throws Exception {
        final IChunkProvider provider = world.getChunkProvider();
        int chunkCount = 0;
        int batchCount = 0;
        for (final List<ChunkPos> region : regions) {
            chunkCount += region.size();
            batchCount += (region.size() + batchSize - 1) / batchSize;
        }

        final long[] batchTimes = new long[batchCount];
        final long[] regionTimes = new long[regions.size()];
        final int[] batchRegionIndices = new int[batchCount];
        final int[] batchFirstIndices = new int[batchCount];
        final int[] batchChunkCounts = new int[batchCount];
        final int[] batchFirstChunkX = new int[batchCount];
        final int[] batchFirstChunkZ = new int[batchCount];
        final int[] batchLastChunkX = new int[batchCount];
        final int[] batchLastChunkZ = new int[batchCount];
        final long[] batchProvideTimes = new long[batchCount];
        final long[] batchBarrierTimes = new long[batchCount];
        final int[] regionChunkCounts = new int[regions.size()];
        final int[] regionBatchCounts = new int[regions.size()];
        final long cpuBefore = probe.engine() == LightProbe.Engine.PULSAR ? LightProbe.pulsarWorkerCpuNanos() : -1;
        final long wallStart = System.nanoTime();
        long provideNanos = 0;
        long barrierNanos = 0;
        int batchIndex = 0;

        for (int regionIndex = 0; regionIndex < regions.size(); ++regionIndex) {
            final List<ChunkPos> region = regions.get(regionIndex);
            final long regionStart = System.nanoTime();
            final int firstBatchInRegion = batchIndex;
            for (int first = 0; first < region.size(); first += batchSize) {
                final int end = Math.min(first + batchSize, region.size());
                final long batchStart = System.nanoTime();
                long batchProvideNanos = 0;
                for (int index = first; index < end; ++index) {
                    final ChunkPos chunk = region.get(index);
                    final long provideStart = System.nanoTime();
                    provider.provideChunk(chunk.x, chunk.z);
                    final long provide = System.nanoTime() - provideStart;
                    provideNanos += provide;
                    batchProvideNanos += provide;
                }
                final long barrier = probe.drainLight();
                barrierNanos += barrier;
                final long batchWall = System.nanoTime() - batchStart;
                batchTimes[batchIndex] = batchWall;
                final ChunkPos firstChunk = region.get(first);
                final ChunkPos lastChunk = region.get(end - 1);
                batchRegionIndices[batchIndex] = regionIndex;
                batchFirstIndices[batchIndex] = first;
                batchChunkCounts[batchIndex] = end - first;
                batchFirstChunkX[batchIndex] = firstChunk.x;
                batchFirstChunkZ[batchIndex] = firstChunk.z;
                batchLastChunkX[batchIndex] = lastChunk.x;
                batchLastChunkZ[batchIndex] = lastChunk.z;
                batchProvideTimes[batchIndex] = batchProvideNanos;
                batchBarrierTimes[batchIndex] = barrier;
                ++batchIndex;
            }
            regionTimes[regionIndex] = System.nanoTime() - regionStart;
            regionChunkCounts[regionIndex] = region.size();
            regionBatchCounts[regionIndex] = batchIndex - firstBatchInRegion;
        }

        final long total = System.nanoTime() - wallStart;
        final long cpuAfter = probe.engine() == LightProbe.Engine.PULSAR ? LightProbe.pulsarWorkerCpuNanos() : -1;
        say(
                sender,
                String.format(
                        Locale.ROOT,
                        "%s: %d chunks | %d batches | provide calls %.2fs | light barriers %.2fs"
                                + " | total-until-lit %.2fs | %.1f chunks/s",
                        label,
                        chunkCount,
                        batchCount,
                        provideNanos * 1.0e-9,
                        barrierNanos * 1.0e-9,
                        total * 1.0e-9,
                        chunkCount / (total * 1.0e-9)));
        report(sender, label + " batch wall (up to " + batchSize + " chunks)", batchTimes);
        if (regionTimes.length > 1) {
            report(sender, label + " region wall (" + regions.get(0).size() + " chunks)", regionTimes);
        }
        if (cpuBefore >= 0 && cpuAfter >= 0) {
            say(
                    sender,
                    String.format(Locale.ROOT, "%s pulsar worker cpu: %.2fs", label, (cpuAfter - cpuBefore) * 1.0e-9));
        }
        final long workerCpuNanos = cpuBefore >= 0 && cpuAfter >= 0 ? cpuAfter - cpuBefore : -1;
        return new BenchmarkPhaseResult(
                label,
                chunkCount,
                batchSize,
                provideNanos,
                barrierNanos,
                total,
                workerCpuNanos,
                new BenchmarkPhaseResult.BatchSamples(
                        batchRegionIndices,
                        batchFirstIndices,
                        batchChunkCounts,
                        batchFirstChunkX,
                        batchFirstChunkZ,
                        batchLastChunkX,
                        batchLastChunkZ,
                        batchProvideTimes,
                        batchBarrierTimes,
                        batchTimes),
                new BenchmarkPhaseResult.RegionSamples(regionChunkCounts, regionBatchCounts, regionTimes));
    }

    static List<List<ChunkPos>> createGenerationTestRegions() {
        final List<List<ChunkPos>> regions = new ArrayList<>(GEN_REGION_COUNT);
        for (int region = 0; region < GEN_REGION_COUNT; ++region) {
            final int center = GEN_TEST_CENTER + region * GEN_REGION_STRIDE;
            regions.add(createSpiralRegion(center, center, GEN_REGION_RADIUS));
        }
        return regions;
    }

    static List<ChunkPos> createFreshnessFootprint(final List<List<ChunkPos>> regions, final int halo) {
        if (halo < 0) {
            throw new IllegalArgumentException("halo must not be negative");
        }
        final Set<ChunkPos> footprint = new LinkedHashSet<>();
        for (final List<ChunkPos> region : regions) {
            if (region.isEmpty()) {
                continue;
            }
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (final ChunkPos chunk : region) {
                minX = Math.min(minX, chunk.x);
                minZ = Math.min(minZ, chunk.z);
                maxX = Math.max(maxX, chunk.x);
                maxZ = Math.max(maxZ, chunk.z);
            }
            for (int x = minX - halo; x <= maxX + halo; ++x) {
                for (int z = minZ - halo; z <= maxZ + halo; ++z) {
                    footprint.add(new ChunkPos(x, z));
                }
            }
        }
        return new ArrayList<>(footprint);
    }

    static ChunkPos findFirstGeneratedChunk(final IChunkProvider provider, final List<ChunkPos> footprint) {
        for (final ChunkPos chunk : footprint) {
            if (provider.isChunkGeneratedAt(chunk.x, chunk.z)) {
                return chunk;
            }
        }
        return null;
    }

    static List<ChunkPos> createSquareRegion(final int centerX, final int centerZ, final int radius) {
        final List<ChunkPos> chunks = new ArrayList<>(squareChunkCount(radius));
        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                chunks.add(new ChunkPos(centerX + dx, centerZ + dz));
            }
        }
        return chunks;
    }

    /** Returns the original lightbench's insertion-ordered, centre-out square-ring traversal. */
    static List<ChunkPos> createSpiralRegion(final int centerX, final int centerZ, final int radius) {
        final List<ChunkPos> chunks = new ArrayList<>(squareChunkCount(radius));
        chunks.add(new ChunkPos(centerX, centerZ));
        for (int ring = 1; ring <= radius; ++ring) {
            for (int x = -ring; x <= ring; ++x) {
                chunks.add(new ChunkPos(centerX + x, centerZ + ring));
            }
            for (int z = ring - 1; z >= -ring; --z) {
                chunks.add(new ChunkPos(centerX + ring, centerZ + z));
            }
            for (int x = ring - 1; x >= -ring; --x) {
                chunks.add(new ChunkPos(centerX + x, centerZ - ring));
            }
            for (int z = ring - 1; z >= -ring + 1; --z) {
                chunks.add(new ChunkPos(centerX - ring, centerZ + z));
            }
        }
        return chunks;
    }

    static int squareChunkCount(final int radius) {
        final int diameter = radius * 2 + 1;
        return diameter * diameter;
    }

    private static boolean isUnsignedInteger(final String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); ++index) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 1.12.2 recreation of Starlight's "block update on platform at y = 254"
     * benchmark: a large stone platform high in the sky, then repeatedly
     * REMOVE a platform block (sky light floods the ~180-block air column
     * below and spreads at the ground) and PLACE it back (the column is
     * shadowed again). Each sample times one edit until the engine has fully
     * converged (engine-specific drain), which is the number a player
     * experiences as a stall. Every rep uses a different column so no run
     * warms the next one's caches.
     */
    private void runEdits(
            final ICommandSender sender, final World world, final LightProbe probe, final int size, final int reps)
            throws Exception {
        final int baseX = EDITS_CENTER_X - size / 2;
        final int baseZ = EDITS_CENTER_Z - size / 2;
        final int y = 254;

        // Load the platform region (plus a margin chunk on each side).
        for (int cx = (baseX >> 4) - 1; cx <= ((baseX + size) >> 4) + 1; ++cx) {
            for (int cz = (baseZ >> 4) - 1; cz <= ((baseZ + size) >> 4) + 1; ++cz) {
                world.getChunkProvider().provideChunk(cx, cz);
            }
        }
        probe.drainLight();

        // Build the platform, then let the engine settle completely.
        final net.minecraft.block.state.IBlockState stone = net.minecraft.init.Blocks.STONE.getDefaultState();
        final long buildStart = System.nanoTime();
        for (int dx = 0; dx < size; ++dx) {
            for (int dz = 0; dz < size; ++dz) {
                world.setBlockState(new BlockPos(baseX + dx, y, baseZ + dz), stone, 2);
            }
        }
        final long buildDrain = probe.drainLight();
        say(
                sender,
                String.format(
                        Locale.ROOT,
                        "platform built: %.2fs (drain %.2fs)",
                        (System.nanoTime() - buildStart) * 1.0e-9,
                        buildDrain * 1.0e-9));

        // Interior spots, spaced 4 blocks apart, one per rep.
        final long[] removeTimes = new long[reps];
        final long[] placeTimes = new long[reps];
        final int perRow = Math.max(1, (size - 8) / 4);
        for (int i = 0; i < reps; ++i) {
            final int sx = baseX + 4 + (i % perRow) * 4;
            final int sz = baseZ + 4 + ((i / perRow) % perRow) * 4;
            final BlockPos spot = new BlockPos(sx, y, sz);

            long t0 = System.nanoTime();
            world.setBlockState(spot, net.minecraft.init.Blocks.AIR.getDefaultState(), 3);
            probe.drainLight();
            removeTimes[i] = System.nanoTime() - t0;

            t0 = System.nanoTime();
            world.setBlockState(spot, stone, 3);
            probe.drainLight();
            placeTimes[i] = System.nanoTime() - t0;
        }

        report(sender, "remove (light floods column)", removeTimes);
        report(sender, "place  (column re-shadowed) ", placeTimes);
    }

    private void report(final ICommandSender sender, final String label, final long[] times) {
        final long[] sorted = times.clone();
        Arrays.sort(sorted);
        final long sum = Arrays.stream(sorted).sum();
        say(
                sender,
                String.format(
                        Locale.ROOT,
                        "%s: avg %.3fms | p50 %.3fms | p99 %.3fms | max %.3fms",
                        label,
                        (sum / (double) sorted.length) * 1.0e-6,
                        percentile(sorted, 0.50) * 1.0e-6,
                        percentile(sorted, 0.99) * 1.0e-6,
                        sorted[sorted.length - 1] * 1.0e-6));
    }

    /** Nearest-rank percentile; {@code sorted} must be non-empty and ascending. */
    static long percentile(final long[] sorted, final double quantile) {
        final int index = Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * quantile) - 1));
        return sorted[index];
    }

    private BenchmarkPhaseResult runBulkPhase(
            final ICommandSender sender,
            final World world,
            final LightProbe probe,
            final String label,
            final int centerX,
            final int centerZ,
            final int radius)
            throws Exception {
        final IChunkProvider provider = world.getChunkProvider();
        final int count = (radius * 2 + 1) * (radius * 2 + 1);

        final long cpuBefore = probe.engine() == LightProbe.Engine.PULSAR ? LightProbe.pulsarWorkerCpuNanos() : -1;
        final long wallStart = System.nanoTime();

        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                provider.provideChunk(centerX + dx, centerZ + dz);
            }
        }

        final long genWall = System.nanoTime() - wallStart;
        final long drain = probe.drainLight();
        final long total = System.nanoTime() - wallStart;
        final long cpuAfter = probe.engine() == LightProbe.Engine.PULSAR ? LightProbe.pulsarWorkerCpuNanos() : -1;

        final String line1 = String.format(
                Locale.ROOT,
                "%s: %d chunks | submit all %.2fs | final light barrier %.2fs"
                        + " | total-until-lit %.2fs | %.1f chunks/s",
                label,
                count,
                genWall * 1.0e-9,
                drain * 1.0e-9,
                total * 1.0e-9,
                count / (total * 1.0e-9));
        say(sender, line1);
        if (cpuBefore >= 0 && cpuAfter >= 0) {
            say(
                    sender,
                    String.format(Locale.ROOT, "%s pulsar worker cpu: %.2fs", label, (cpuAfter - cpuBefore) * 1.0e-9));
        }

        queueUnloadSquare(provider, centerX, centerZ, radius);
        final long workerCpuNanos = cpuBefore >= 0 && cpuAfter >= 0 ? cpuAfter - cpuBefore : -1;
        return new BenchmarkPhaseResult(
                label,
                count,
                count,
                genWall,
                drain,
                total,
                workerCpuNanos,
                new BenchmarkPhaseResult.BatchSamples(
                        new int[] {0},
                        new int[] {0},
                        new int[] {count},
                        new int[] {centerX - radius},
                        new int[] {centerZ - radius},
                        new int[] {centerX + radius},
                        new int[] {centerZ + radius},
                        new long[] {genWall},
                        new long[] {drain},
                        new long[] {total}),
                new BenchmarkPhaseResult.RegionSamples(new int[] {count}, new int[] {1}, new long[] {total}));
    }

    private static void queueUnloadSquare(
            final IChunkProvider provider, final int centerX, final int centerZ, final int radius) {
        queueUnloadRegions(provider, Collections.singletonList(createSquareRegion(centerX, centerZ, radius)));
    }

    /** Marks only benchmark chunks for normal server-tick unloading after this synchronous command returns. */
    private static void queueUnloadRegions(final IChunkProvider provider, final List<List<ChunkPos>> regions) {
        if (!(provider instanceof ChunkProviderServer)) {
            return;
        }
        final ChunkProviderServer serverProvider = (ChunkProviderServer) provider;
        for (final List<ChunkPos> region : regions) {
            for (final ChunkPos position : region) {
                final Chunk chunk = serverProvider.getLoadedChunk(position.x, position.z);
                if (chunk != null) {
                    serverProvider.queueUnload(chunk);
                }
            }
        }
    }

    private static final class PreflightResult {

        private final boolean fresh;
        private final int footprintChunks;
        private final long elapsedNanos;

        private PreflightResult(final boolean fresh, final int footprintChunks, final long elapsedNanos) {
            this.fresh = fresh;
            this.footprintChunks = footprintChunks;
            this.elapsedNanos = elapsedNanos;
        }
    }

    private static void say(final ICommandSender sender, final String message) {
        Lightbench.LOGGER.info(message);
        sender.sendMessage(new TextComponentString("§e[lightbench]§r " + message));
    }
}

package com.sumirelabs.lightbench;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;

import java.util.Arrays;
import java.util.Locale;

/**
 * {@code /lightbench [radius] [warmupRadius]} — deterministic generation
 * benchmark (defaults 50 → 101×101 = 10201 chunks, warmup 10 → 441).
 *
 * <p>Warmup region is centred on chunk (-625, -625), the measured region on
 * chunk (625, 625) — the same ±10000-block offsets the original lightbench
 * uses, far away from spawn so every chunk is freshly generated.
 */
public class CommandLightbench extends CommandBase {

    private static final int WARMUP_CENTER = -625;
    private static final int TEST_CENTER = 625;
    private static final int EDITS_CENTER_X = 20000;
    private static final int EDITS_CENTER_Z = 20000;

    @Override
    public String getName() {
        return "lightbench";
    }

    @Override
    public String getUsage(final ICommandSender sender) {
        return "/lightbench [radius] [warmupRadius] | /lightbench edits [size] [reps]"
                + " | /lightbench tps <editsPerTick> [seconds] | /lightbench tps sweep [seconds]"
                + " | /lightbench spikes [editsPerSec] [seconds]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(final MinecraftServer server, final ICommandSender sender, final String[] args) throws CommandException {
        final World world = sender.getEntityWorld();
        final LightProbe probe = LightProbe.create(world);

        try {
            if (args.length > 0 && "spikes".equals(args[0])) {
                if (TpsTest.isRunning() || SpikeTest.isRunning()) {
                    say(sender, "a tick-measured run is already in progress");
                    return;
                }
                final int size = 64;
                final int editsPerSec = args.length > 1 ? parseInt(args[1], 1, 20) : 2;
                final int seconds = args.length > 2 ? parseInt(args[2], 10, 600) : 60;
                SpikeTest.start(sender, world, editsPerSec, seconds,
                        EDITS_CENTER_X - size / 2, EDITS_CENTER_Z - size / 2, size);
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
                    TpsTest.startSweep(sender, world, seconds,
                            new int[]{64, 128, 256, 512, 1024, 2048}, baseX, baseZ, size);
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
                say(sender, "engine: " + probe.engine().name().toLowerCase(Locale.ROOT)
                        + " | edits test: platform " + size + "x" + size + " at y=254, " + reps + " reps");
                runEdits(sender, world, probe, size, reps);
                return;
            }

            final int radius = args.length > 0 ? parseInt(args[0], 1, 200) : 50;
            final int warmupRadius = args.length > 1 ? parseInt(args[1], 0, 200) : 10;
            say(sender, "engine: " + probe.engine().name().toLowerCase(Locale.ROOT)
                    + " | seed: " + world.getSeed()
                    + " | radius " + radius + " (warmup " + warmupRadius + ")");
            if (warmupRadius > 0) {
                runPhase(sender, world, probe, "warmup", WARMUP_CENTER, WARMUP_CENTER, warmupRadius);
            }
            runPhase(sender, world, probe, "test", TEST_CENTER, TEST_CENTER, radius);
        } catch (final Exception e) {
            Lightbench.LOGGER.error("lightbench failed", e);
            throw new CommandException("lightbench failed: " + e);
        }
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
    private void runEdits(final ICommandSender sender, final World world, final LightProbe probe,
                          final int size, final int reps) throws Exception {
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
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < size; ++dx) {
            for (int dz = 0; dz < size; ++dz) {
                world.setBlockState(new BlockPos(baseX + dx, y, baseZ + dz), stone, 2);
            }
        }
        final long buildDrain = probe.drainLight();
        say(sender, String.format(Locale.ROOT, "platform built: %.2fs (drain %.2fs)",
                (System.nanoTime() - buildStart) * 1.0e-9, buildDrain * 1.0e-9));

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
        say(sender, String.format(Locale.ROOT,
                "%s: avg %.3fms | p50 %.3fms | p99 %.3fms | max %.3fms",
                label,
                (sum / (double) sorted.length) * 1.0e-6,
                sorted[sorted.length / 2] * 1.0e-6,
                sorted[(int) Math.min(sorted.length - 1L, (long) Math.ceil(sorted.length * 0.99))] * 1.0e-6,
                sorted[sorted.length - 1] * 1.0e-6));
    }

    private void runPhase(final ICommandSender sender, final World world, final LightProbe probe,
                          final String label, final int centerX, final int centerZ, final int radius) throws Exception {
        final IChunkProvider provider = world.getChunkProvider();
        final int count = (radius * 2 + 1) * (radius * 2 + 1);
        final long[] perChunk = new long[count];

        final long cpuBefore = probe.engine() == LightProbe.Engine.PULSAR ? LightProbe.pulsarWorkerCpuNanos() : -1;
        final long wallStart = System.nanoTime();

        int i = 0;
        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                final long t0 = System.nanoTime();
                provider.provideChunk(centerX + dx, centerZ + dz);
                perChunk[i++] = System.nanoTime() - t0;
            }
        }

        final long genWall = System.nanoTime() - wallStart;
        final long drain = probe.drainLight();
        final long total = System.nanoTime() - wallStart;
        final long cpuAfter = probe.engine() == LightProbe.Engine.PULSAR ? LightProbe.pulsarWorkerCpuNanos() : -1;

        Arrays.sort(perChunk);
        final long sum = Arrays.stream(perChunk).sum();
        final String line1 = String.format(Locale.ROOT,
                "%s: %d chunks | gen %.2fs | light drain %.2fs | total-until-lit %.2fs",
                label, count, genWall * 1.0e-9, drain * 1.0e-9, total * 1.0e-9);
        final String line2 = String.format(Locale.ROOT,
                "%s chunk times: avg %.2fms | p50 %.2fms | p99 %.2fms | max %.2fms",
                label, (sum / (double) count) * 1.0e-6,
                perChunk[count / 2] * 1.0e-6,
                perChunk[(int) Math.min(count - 1L, (long) Math.ceil(count * 0.99))] * 1.0e-6,
                perChunk[count - 1] * 1.0e-6);
        say(sender, line1);
        say(sender, line2);
        if (cpuBefore >= 0 && cpuAfter >= 0) {
            say(sender, String.format(Locale.ROOT,
                    "%s pulsar worker cpu: %.2fs", label, (cpuAfter - cpuBefore) * 1.0e-9));
        }

        // Keep memory flat between phases; the measured work is already done.
        if (provider instanceof ChunkProviderServer) {
            ((ChunkProviderServer) provider).queueUnloadAll();
        }
    }

    private static void say(final ICommandSender sender, final String message) {
        Lightbench.LOGGER.info(message);
        sender.sendMessage(new TextComponentString("§e[lightbench]§r " + message));
    }
}

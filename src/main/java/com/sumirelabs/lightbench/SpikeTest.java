package com.sumirelabs.lightbench;

import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

/**
 * Lag-spike benchmark ("spikes mode").
 *
 * <p>Where tps mode asks "how much edit throughput until MSPT saturates",
 * this mode asks the player-felt question: <b>at a realistic building pace,
 * how often and how hard does the tick thread stall?</b> A handful of edits
 * per second is what actual building produces, and at that rate every
 * engine's AVERAGE tick time is fine — the difference between engines is
 * entirely in the spikes, which is what a player feels as stutter.
 *
 * <p>Method: toggle one random block of the y=254 platform every
 * {@code 20/editsPerSec} ticks (each toggle floods or re-shadows a
 * ~190-block sky column — the expensive light op) and record EVERY server
 * tick's work time (Phase.START → Phase.END), edit ticks and idle ticks
 * alike. No per-edit drain: vanilla pays inline, Alfheim pays when its
 * deferred queue is flushed on the tick thread, Pulsar hands the work to
 * its workers — each engine's tick thread shows exactly what a live server
 * would experience. The post-run backlog drain is reported separately so
 * async engines cannot hide unfinished work.
 *
 * <p>Output: chat/log summary (percentiles, spike-bucket counts, worst
 * ticks) plus a per-tick CSV next to the game directory for timeline
 * charts. Determinism: fixed RNG seed and a platform rebuilt to full stone
 * before every run, so every engine executes the identical op sequence.
 */
public final class SpikeTest {

    private static SpikeTest active;

    private final ICommandSender sender;
    private final World world;
    private final LightProbe probe;
    private final int editsPerSec;
    private final int baseX;
    private final int baseZ;
    private final int size;
    private final Random rng = new Random(0x5EEDL);
    private final long[] tickNanos;
    private final IBlockState stone = Blocks.STONE.getDefaultState();

    private int tickIndex;
    private long tickStart;
    private boolean armed; // skip the (partial) tick the run was started in
    private int editAcc;
    private long opsDone;
    private final long cpuBefore;
    // Same guard as TpsTest: whatever comes first ends the run — the tick
    // cap or 3x the requested seconds of wall time.
    private long wallDeadline;

    private SpikeTest(
            final ICommandSender sender,
            final World world,
            final LightProbe probe,
            final int editsPerSec,
            final int seconds,
            final int baseX,
            final int baseZ,
            final int size) {
        this.sender = sender;
        this.world = world;
        this.probe = probe;
        this.editsPerSec = editsPerSec;
        this.baseX = baseX;
        this.baseZ = baseZ;
        this.size = size;
        this.tickNanos = new long[seconds * 20];
        this.cpuBefore = probe.engine() == LightProbe.Engine.PULSAR ? LightProbe.pulsarWorkerCpuNanos() : -1;
    }

    public static boolean isRunning() {
        return active != null;
    }

    /** Queue a single run. Platform prep happens synchronously here (untimed). */
    public static void start(
            final ICommandSender sender,
            final World world,
            final int editsPerSec,
            final int seconds,
            final int baseX,
            final int baseZ,
            final int size)
            throws Exception {
        final LightProbe probe = LightProbe.create(world);
        say(
                sender,
                String.format(
                        Locale.ROOT,
                        "engine: %s | spike test: %d edits/s for %ds (platform %dx%d at y=254, every tick recorded)",
                        probe.engine().name().toLowerCase(Locale.ROOT),
                        editsPerSec,
                        seconds,
                        size,
                        size));
        TpsTest.prepPlatform(world, probe, baseX, baseZ, size);
        active = new SpikeTest(sender, world, probe, editsPerSec, seconds, baseX, baseZ, size);
    }

    // ---- tick plumbing (called from Lightbench's ServerTickEvent handler) ----

    public static void onTickStart() {
        final SpikeTest t = active;
        if (t != null) {
            t.tickStart = System.nanoTime();
            t.armed = true;
        }
    }

    public static void onTickEnd() {
        final SpikeTest t = active;
        if (t != null && t.armed) {
            t.tick();
        }
    }

    private void tick() {
        if (this.wallDeadline == 0) {
            this.wallDeadline = System.nanoTime() + this.tickNanos.length / 20L * 3_000_000_000L;
        }
        // Pace edits evenly across the second (accumulator handles any 1..20 rate).
        this.editAcc += this.editsPerSec;
        if (this.editAcc >= 20) {
            this.editAcc -= 20;
            final int x = this.baseX + 4 + this.rng.nextInt(this.size - 8);
            final int z = this.baseZ + 4 + this.rng.nextInt(this.size - 8);
            final BlockPos p = new BlockPos(x, 254, z);
            final boolean isStone = this.world.getBlockState(p).getBlock() == Blocks.STONE;
            this.world.setBlockState(p, isStone ? Blocks.AIR.getDefaultState() : this.stone, 3);
            ++this.opsDone;
        }
        this.tickNanos[this.tickIndex++] = System.nanoTime() - this.tickStart;
        if (this.tickIndex == this.tickNanos.length || System.nanoTime() > this.wallDeadline) {
            finish();
        }
    }

    private void finish() {
        active = null;
        try {
            final long drain = this.probe.drainLight();
            final long cpuAfter = this.cpuBefore >= 0 ? LightProbe.pulsarWorkerCpuNanos() : -1;
            final String engine = this.probe.engine().name().toLowerCase(Locale.ROOT);

            if (this.tickIndex < this.tickNanos.length) {
                say(
                        this.sender,
                        String.format(
                                Locale.ROOT,
                                "spikes: wall-capped after %d ticks (engine too slow for the full %d)",
                                this.tickIndex,
                                this.tickNanos.length));
            }

            // Per-tick CSV for timeline charts.
            final File csv = new File(
                    ".",
                    String.format(
                            Locale.ROOT,
                            "lightbench-spikes-%s-%deps-%d.csv",
                            engine,
                            this.editsPerSec,
                            System.currentTimeMillis()));
            try (final PrintWriter out = new PrintWriter(csv, "UTF-8")) {
                out.println("tick,ms");
                for (int i = 0; i < this.tickIndex; ++i) {
                    out.printf(Locale.ROOT, "%d,%.3f%n", i, this.tickNanos[i] * 1.0e-6);
                }
            }

            final long[] sorted = Arrays.copyOf(this.tickNanos, this.tickIndex);
            Arrays.sort(sorted);
            final double avgMs = Arrays.stream(sorted).sum() / (double) sorted.length * 1.0e-6;
            int over25 = 0, over50 = 0, over100 = 0, over250 = 0;
            for (final long t : sorted) {
                if (t > 25_000_000L) ++over25;
                if (t > 50_000_000L) ++over50;
                if (t > 100_000_000L) ++over100;
                if (t > 250_000_000L) ++over250;
            }
            say(
                    this.sender,
                    String.format(
                            Locale.ROOT,
                            "spikes %d edits/s: mspt avg %.2f | p50 %.2f | p99 %.2f | max %.2f (%d ticks, %d edits)",
                            this.editsPerSec,
                            avgMs,
                            sorted[sorted.length / 2] * 1.0e-6,
                            sorted[(int) Math.min(sorted.length - 1L, (long) Math.ceil(sorted.length * 0.99))] * 1.0e-6,
                            sorted[sorted.length - 1] * 1.0e-6,
                            this.tickIndex,
                            this.opsDone));
            say(
                    this.sender,
                    String.format(
                            Locale.ROOT,
                            "spikes: ticks >25ms %d | >50ms %d | >100ms %d | >250ms %d",
                            over25,
                            over50,
                            over100,
                            over250));
            say(this.sender, "spikes: worst ticks — " + worstTicks(5));
            final StringBuilder extra = new StringBuilder(
                    String.format(Locale.ROOT, "spikes: post-run light backlog drain %.2fs", drain * 1.0e-9));
            if (cpuAfter >= 0) {
                extra.append(
                        String.format(Locale.ROOT, " | pulsar worker cpu %.2fs", (cpuAfter - this.cpuBefore) * 1.0e-9));
            }
            say(this.sender, extra.toString());
            say(this.sender, "spikes: per-tick csv -> " + csv.getAbsolutePath());
        } catch (final Exception e) {
            Lightbench.LOGGER.error("spike run failed", e);
            say(this.sender, "spike run failed: " + e);
        }
    }

    /** Top-N ticks by duration, in "#tick 123.4ms (t=6.2s)" form. */
    private String worstTicks(final int n) {
        final Integer[] order = new Integer[this.tickIndex];
        for (int i = 0; i < this.tickIndex; ++i) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(this.tickNanos[b], this.tickNanos[a]));
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, order.length); ++i) {
            if (i > 0) {
                sb.append(", ");
            }
            final int idx = order[i];
            sb.append(
                    String.format(Locale.ROOT, "#%d %.0fms (t=%.1fs)", idx, this.tickNanos[idx] * 1.0e-6, idx / 20.0));
        }
        return sb.toString();
    }

    private static void say(final ICommandSender sender, final String message) {
        Lightbench.LOGGER.info(message);
        try {
            sender.sendMessage(new TextComponentString("§e[lightbench]§r " + message));
        } catch (final Exception ignored) {
        }
    }
}

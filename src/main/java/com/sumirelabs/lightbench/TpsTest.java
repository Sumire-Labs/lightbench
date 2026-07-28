package com.sumirelabs.lightbench;

import net.minecraft.block.state.IBlockState;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Sustained-edit-load MSPT benchmark ("tps mode").
 *
 * <p>Every server tick, {@code editsPerTick} random blocks of the y=254
 * platform are toggled stone↔air (each toggle floods or re-shadows a
 * ~190-block sky column — the expensive light op). The harness records how
 * long each tick's WORK took (Phase.START → after the edits in Phase.END),
 * i.e. the part of the 50 ms budget actually consumed.
 *
 * <p>This axis is deliberately measured WITHOUT any per-edit drain: vanilla
 * pays its light cost inline in {@code setBlockState}, Alfheim pays on the
 * tick thread when its deferred queue is flushed by the next light read, and
 * Pulsar hands the work to its workers — whatever each engine leaves on the
 * tick thread is exactly what the measurement captures, because that is what
 * a server's TPS experiences. The light backlog still outstanding when the
 * run ends is reported separately (post-run drain), so async engines cannot
 * hide unfinished work.
 *
 * <p>Determinism: a fixed RNG seed and a platform rebuilt to full stone
 * before every run mean every engine executes the identical op sequence on
 * identical state. All entry points run on the server thread.
 */
public final class TpsTest {

    private static final int LADDER_ABORT_AVG_MSPT = 250;

    private static TpsTest active;
    private static final ArrayDeque<int[]> queue = new ArrayDeque<>(); // {editsPerTick, seconds}

    private final ICommandSender sender;
    private final World world;
    private final LightProbe probe;
    private final int editsPerTick;
    private final int baseX;
    private final int baseZ;
    private final int size;
    private final Random rng = new Random(0x5EEDL);
    private final long[] tickNanos;
    private final IBlockState stone = Blocks.STONE.getDefaultState();

    private int tickIndex;
    private long tickStart;
    private boolean armed; // skip the (partial) tick the run was started in
    private final long cpuBefore;
    private long opsDone;
    // Slow engines can take seconds per tick, so a pure tick-count budget
    // would run for ages. Whatever comes first ends the run: the tick cap
    // or 3x the requested seconds of wall time.
    private long wallDeadline;

    private TpsTest(final ICommandSender sender, final World world, final LightProbe probe,
                    final int editsPerTick, final int seconds, final int baseX, final int baseZ, final int size) {
        this.sender = sender;
        this.world = world;
        this.probe = probe;
        this.editsPerTick = editsPerTick;
        this.baseX = baseX;
        this.baseZ = baseZ;
        this.size = size;
        this.tickNanos = new long[seconds * 20];
        this.cpuBefore = probe.engine() == LightProbe.Engine.PULSAR ? LightProbe.pulsarWorkerCpuNanos() : -1;
    }

    public static boolean isRunning() {
        return active != null || !queue.isEmpty();
    }

    /** Queue a single run. Platform prep happens synchronously here (untimed). */
    public static void start(final ICommandSender sender, final World world,
                             final int editsPerTick, final int seconds,
                             final int baseX, final int baseZ, final int size) throws Exception {
        start(sender, world, editsPerTick, seconds, baseX, baseZ, size, true);
    }

    private static void start(final ICommandSender sender, final World world,
                              final int editsPerTick, final int seconds,
                              final int baseX, final int baseZ, final int size,
                              final boolean prep) throws Exception {
        final LightProbe probe = LightProbe.create(world);
        say(sender, String.format(Locale.ROOT,
                "engine: %s | tps test: %d edits/tick for %ds (platform %dx%d at y=254)",
                probe.engine().name().toLowerCase(Locale.ROOT), editsPerTick, seconds, size, size));
        if (prep) {
            prepPlatform(world, probe, baseX, baseZ, size);
        }
        active = new TpsTest(sender, world, probe, editsPerTick, seconds, baseX, baseZ, size);
    }

    /**
     * Queue a ladder of runs executed back to back on the same platform.
     * The platform is rebuilt once up front only: every engine runs the same
     * seeded ladder, so the platform state entering step N is identical
     * across engines and rebuilding between steps (minutes of setBlockState
     * on slow engines) would add nothing but wall time.
     */
    public static void startSweep(final ICommandSender sender, final World world,
                                  final int seconds, final int[] ladder,
                                  final int baseX, final int baseZ, final int size) throws Exception {
        for (final int k : ladder) {
            queue.add(new int[]{k, seconds, baseX, baseZ, size});
        }
        say(sender, "tps sweep: ladder " + Arrays.toString(ladder) + ", " + seconds + "s each");
        prepPlatform(world, LightProbe.create(world), baseX, baseZ, size);
        startNext(sender, world);
    }

    private static void startNext(final ICommandSender sender, final World world) throws Exception {
        final int[] next = queue.poll();
        if (next != null) {
            start(sender, world, next[0], next[1], next[2], next[3], next[4], false);
        }
    }

    /**
     * Rebuild the platform to full stone and let light settle, so every run
     * starts from the same state regardless of what earlier runs toggled.
     * Package-private: {@link SpikeTest} preps the identical platform.
     */
    static void prepPlatform(final World world, final LightProbe probe,
                             final int baseX, final int baseZ, final int size) throws Exception {
        for (int cx = (baseX >> 4) - 1; cx <= ((baseX + size) >> 4) + 1; ++cx) {
            for (int cz = (baseZ >> 4) - 1; cz <= ((baseZ + size) >> 4) + 1; ++cz) {
                world.getChunkProvider().provideChunk(cx, cz);
            }
        }
        final IBlockState stone = Blocks.STONE.getDefaultState();
        for (int dx = 0; dx < size; ++dx) {
            for (int dz = 0; dz < size; ++dz) {
                final BlockPos p = new BlockPos(baseX + dx, 254, baseZ + dz);
                if (world.getBlockState(p).getBlock() != Blocks.STONE) {
                    world.setBlockState(p, stone, 3);
                }
            }
        }
        probe.drainLight();
    }

    // ---- tick plumbing (called from Lightbench's ServerTickEvent handler) ----

    public static void onTickStart() {
        final TpsTest t = active;
        if (t != null) {
            t.tickStart = System.nanoTime();
            t.armed = true;
        }
    }

    public static void onTickEnd() {
        final TpsTest t = active;
        if (t != null && t.armed) {
            t.tick();
        }
    }

    private void tick() {
        if (this.wallDeadline == 0) {
            this.wallDeadline = System.nanoTime() + this.tickNanos.length / 20L * 3_000_000_000L;
        }
        for (int i = 0; i < this.editsPerTick; ++i) {
            final int x = this.baseX + 4 + this.rng.nextInt(this.size - 8);
            final int z = this.baseZ + 4 + this.rng.nextInt(this.size - 8);
            final BlockPos p = new BlockPos(x, 254, z);
            final boolean isStone = this.world.getBlockState(p).getBlock() == Blocks.STONE;
            this.world.setBlockState(p, isStone ? Blocks.AIR.getDefaultState() : this.stone, 3);
        }
        this.opsDone += this.editsPerTick;
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

            if (this.tickIndex < this.tickNanos.length) {
                say(this.sender, String.format(Locale.ROOT,
                        "tps %d edits/tick: wall-capped after %d ticks (engine too slow for the full %d)",
                        this.editsPerTick, this.tickIndex, this.tickNanos.length));
            }
            final long[] sorted = Arrays.copyOf(this.tickNanos, this.tickIndex);
            Arrays.sort(sorted);
            final double avgMs = Arrays.stream(sorted).sum() / (double) sorted.length * 1.0e-6;
            int over50 = 0;
            for (final long t : sorted) {
                if (t > 50_000_000L) {
                    ++over50;
                }
            }
            say(this.sender, String.format(Locale.ROOT,
                    "tps %d edits/tick: mspt avg %.2f | p50 %.2f | p99 %.2f | max %.2f | ticks>50ms %d/%d",
                    this.editsPerTick, avgMs,
                    sorted[sorted.length / 2] * 1.0e-6,
                    sorted[(int) Math.min(sorted.length - 1L, (long) Math.ceil(sorted.length * 0.99))] * 1.0e-6,
                    sorted[sorted.length - 1] * 1.0e-6,
                    over50, sorted.length));
            final StringBuilder extra = new StringBuilder(String.format(Locale.ROOT,
                    "tps %d edits/tick: ops %d | post-run light backlog drain %.2fs",
                    this.editsPerTick, this.opsDone, drain * 1.0e-9));
            if (cpuAfter >= 0) {
                extra.append(String.format(Locale.ROOT, " | pulsar worker cpu %.2fs",
                        (cpuAfter - this.cpuBefore) * 1.0e-9));
            }
            say(this.sender, extra.toString());

            if (avgMs > LADDER_ABORT_AVG_MSPT && !queue.isEmpty()) {
                queue.clear();
                say(this.sender, "tps sweep: saturated (avg mspt > " + LADDER_ABORT_AVG_MSPT + "), remaining steps skipped");
                return;
            }
            // light already settled by the drain above; chain the next ladder step
            if (!queue.isEmpty()) {
                startNext(this.sender, this.world);
            }
        } catch (final Exception e) {
            queue.clear();
            Lightbench.LOGGER.error("tps run failed", e);
            say(this.sender, "tps run failed: " + e);
        }
    }

    private static void say(final ICommandSender sender, final String message) {
        Lightbench.LOGGER.info(message);
        try {
            sender.sendMessage(new TextComponentString("§e[lightbench]§r " + message));
        } catch (final Exception ignored) {
        }
    }
}

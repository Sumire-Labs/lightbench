package com.sumirelabs.lightbench;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

/**
 * Engine detection + the minimal per-engine adapters, all via reflection so
 * this mod compiles and runs against any combination of engines.
 */
public final class LightProbe {

    public enum Engine {
        PULSAR,
        ALFHEIM,
        VANILLA
    }

    private final Engine engine;
    private final Object pulsarManager; // com.sumirelabs.pulsar.light.WorldLightManager
    private final Method pulsarHasUpdates;
    private final Method pulsarAwaitPendingWork;
    private final Object alfheimEngine; // dev.redstudio.alfheim.lighting.LightingEngine
    private final Method alfheimProcess;
    private final Method alfheimCachedLight;

    private LightProbe(
            final Engine engine,
            final Object pulsarManager,
            final Method pulsarHasUpdates,
            final Method pulsarAwaitPendingWork,
            final Object alfheimEngine,
            final Method alfheimProcess,
            final Method alfheimCachedLight) {
        this.engine = engine;
        this.pulsarManager = pulsarManager;
        this.pulsarHasUpdates = pulsarHasUpdates;
        this.pulsarAwaitPendingWork = pulsarAwaitPendingWork;
        this.alfheimEngine = alfheimEngine;
        this.alfheimProcess = alfheimProcess;
        this.alfheimCachedLight = alfheimCachedLight;
    }

    public static LightProbe create(final World world) {
        try {
            final Class<?> pulsarWorld = Class.forName("com.sumirelabs.pulsar.world.PulsarWorld");
            if (pulsarWorld.isInstance(world)) {
                final Object manager =
                        pulsarWorld.getMethod("pulsar$getLightManager").invoke(world);
                if (manager != null) {
                    final Method hasUpdates = manager.getClass().getMethod("hasUpdates");
                    Method awaitPendingWork = null;
                    try {
                        awaitPendingWork = manager.getClass().getMethod("awaitPendingWork", int.class, int.class);
                    } catch (final NoSuchMethodException ignored) {
                        // Older Pulsar builds expose only the global pending-work probe.
                    }
                    return new LightProbe(Engine.PULSAR, manager, hasUpdates, awaitPendingWork, null, null, null);
                }
            }
        } catch (final Throwable ignored) {
        }
        try {
            final Class<?> provider = Class.forName("dev.redstudio.alfheim.api.ILightingEngineProvider");
            if (provider.isInstance(world)) {
                final Object engine =
                        provider.getMethod("getAlfheim$lightingEngine").invoke(world);
                if (engine != null) {
                    final Method process = engine.getClass().getMethod("processLightUpdates");
                    Method cachedLight = null;
                    try {
                        cachedLight = Class.forName("dev.redstudio.alfheim.api.IChunkLightingData")
                                .getMethod("alfheim$getCachedLightFor", EnumSkyBlock.class, BlockPos.class);
                    } catch (final ReflectiveOperationException ignored) {
                        // Update-result validation will refuse a flushing fallback.
                    }
                    return new LightProbe(Engine.ALFHEIM, null, null, null, engine, process, cachedLight);
                }
            }
        } catch (final Throwable ignored) {
        }
        return new LightProbe(Engine.VANILLA, null, null, null, null, null, null);
    }

    public Engine engine() {
        return this.engine;
    }

    public String completionBarrierName() {
        switch (this.engine) {
            case PULSAR:
                return this.pulsarAwaitPendingWork == null
                        ? "pulsar_global_pending_poll"
                        : "pulsar_chunk_future_then_global_pending";
            case ALFHEIM:
                return "alfheim_process_light_updates";
            case VANILLA:
                return "vanilla_inline";
            default:
                throw new IllegalStateException("unknown light engine " + this.engine);
        }
    }

    public String rawLightReaderName() {
        return this.engine == Engine.ALFHEIM ? "alfheim_cached_light" : "world_stored_light";
    }

    public void requireUpdateBenchmarkSupport() {
        if (this.engine == Engine.PULSAR && this.pulsarAwaitPendingWork == null) {
            throw new IllegalStateException(
                    "this Pulsar build lacks the per-chunk completion future required by the update benchmark");
        }
        if (this.engine == Engine.ALFHEIM && this.alfheimCachedLight == null) {
            throw new IllegalStateException(
                    "this Alfheim build lacks the non-flushing light reader required by the update benchmark");
        }
    }

    /**
     * Block until the engine has fully lit everything generated so far.
     * Returns the wall nanos spent draining. Vanilla lights inline → 0.
     */
    public long drainLight() throws Exception {
        return drainLight(null);
    }

    /**
     * Position-aware completion barrier for update-latency samples. Current
     * Pulsar builds expose an awaitable per-chunk future, which avoids adding
     * millisecond polling granularity to short samples. The global queue check
     * remains the final correctness barrier and the fallback for older builds.
     */
    public long drainLight(final BlockPos affectedPosition) throws Exception {
        if (this.engine == Engine.VANILLA) {
            return 0;
        }
        final long start = System.nanoTime();
        switch (this.engine) {
            case PULSAR:
                if (affectedPosition != null && this.pulsarAwaitPendingWork != null) {
                    this.pulsarAwaitPendingWork.invoke(
                            this.pulsarManager, affectedPosition.getX() >> 4, affectedPosition.getZ() >> 4);
                }
                // Async workers: poll until both queues are empty. Busy-spin
                // for the first few ms so sub-millisecond drains are not
                // quantised by sleep granularity (matters for the edits test).
                final long spinUntil = start + 5_000_000L;
                final long deadline = start + 300_000_000_000L; // 5 min safety
                while ((Boolean) this.pulsarHasUpdates.invoke(this.pulsarManager)) {
                    final long now = System.nanoTime();
                    if (now > deadline) {
                        throw new IllegalStateException("Pulsar queues did not drain within 5 minutes");
                    }
                    if (now < spinUntil) {
                        Thread.yield();
                    } else {
                        Thread.sleep(1);
                    }
                }
                break;
            case ALFHEIM:
                // Deferred queue, normally flushed on reads: flush it all now.
                this.alfheimProcess.invoke(this.alfheimEngine);
                break;
            case VANILLA:
                break;
        }
        return System.nanoTime() - start;
    }

    /**
     * Read a stored light value without processing deferred work. Alfheim's
     * normal Chunk#getLightFor method drains its queue before reading, which
     * could otherwise hide an early-returning completion barrier during the
     * out-of-band correctness check.
     */
    public int readRawLight(final World world, final EnumSkyBlock lightType, final BlockPos position) throws Exception {
        if (this.engine == Engine.ALFHEIM) {
            if (this.alfheimCachedLight == null) {
                throw new IllegalStateException("Alfheim does not expose non-flushing cached-light reads");
            }
            final Number value = (Number) this.alfheimCachedLight.invoke(world.getChunk(position), lightType, position);
            return value.intValue() & 0xff;
        }
        return world.getLightFor(lightType, position);
    }

    /** Total CPU nanos of all live threads whose name starts with {@code Pulsar-}. */
    public static long pulsarWorkerCpuNanos() {
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported()) {
            return -1;
        }
        if (!bean.isThreadCpuTimeEnabled()) {
            try {
                bean.setThreadCpuTimeEnabled(true);
            } catch (final SecurityException | UnsupportedOperationException ignored) {
                return -1;
            }
        }
        long total = 0;
        for (final long id : bean.getAllThreadIds()) {
            final ThreadInfo info = bean.getThreadInfo(id);
            if (info == null || !info.getThreadName().startsWith("Pulsar-")) {
                continue;
            }
            final long cpu = bean.getThreadCpuTime(id);
            if (cpu > 0) {
                total += cpu;
            }
        }
        return total;
    }
}

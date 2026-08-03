package com.sumirelabs.lightbench;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
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
    private final Object alfheimEngine; // dev.redstudio.alfheim.lighting.LightingEngine
    private final Method alfheimProcess;

    private LightProbe(
            final Engine engine,
            final Object pulsarManager,
            final Method pulsarHasUpdates,
            final Object alfheimEngine,
            final Method alfheimProcess) {
        this.engine = engine;
        this.pulsarManager = pulsarManager;
        this.pulsarHasUpdates = pulsarHasUpdates;
        this.alfheimEngine = alfheimEngine;
        this.alfheimProcess = alfheimProcess;
    }

    public static LightProbe create(final World world) {
        try {
            final Class<?> pulsarWorld = Class.forName("com.sumirelabs.pulsar.world.PulsarWorld");
            if (pulsarWorld.isInstance(world)) {
                final Object manager =
                        pulsarWorld.getMethod("pulsar$getLightManager").invoke(world);
                if (manager != null) {
                    final Method hasUpdates = manager.getClass().getMethod("hasUpdates");
                    return new LightProbe(Engine.PULSAR, manager, hasUpdates, null, null);
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
                    return new LightProbe(Engine.ALFHEIM, null, null, engine, process);
                }
            }
        } catch (final Throwable ignored) {
        }
        return new LightProbe(Engine.VANILLA, null, null, null, null);
    }

    public Engine engine() {
        return this.engine;
    }

    /**
     * Block until the engine has fully lit everything generated so far.
     * Returns the wall nanos spent draining. Vanilla lights inline → 0.
     */
    public long drainLight() throws Exception {
        final long start = System.nanoTime();
        switch (this.engine) {
            case PULSAR:
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

    /** Total CPU nanos of all live threads whose name starts with {@code Pulsar-}. */
    public static long pulsarWorkerCpuNanos() {
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported()) {
            return -1;
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

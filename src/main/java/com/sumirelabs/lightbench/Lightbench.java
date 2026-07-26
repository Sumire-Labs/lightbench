package com.sumirelabs.lightbench;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 1.12.2 port of the methodology of ca.spottedleaf/lightbench (LGPL-3.0):
 * generate a fixed, deterministic region of fresh chunks and measure how
 * long the installed light engine takes to fully light it.
 *
 * <p>1.12.2 has no dedicated light thread and chunk generation is
 * synchronous, so instead of wrapping the light mailbox this port measures,
 * per engine and without any bytecode hooks:
 *
 * <ul>
 *   <li>wall time per generated chunk (avg / p50 / p99 / max — spike view)</li>
 *   <li>post-generation light drain: Pulsar works asynchronously, so the
 *       harness waits until its queues are empty; Alfheim defers into a
 *       queue flushed on read, so the harness times one full
 *       {@code processLightUpdates()}; vanilla lights inline (drain = 0)</li>
 *   <li>total wall time until the region is generated AND fully lit — the
 *       headline number that is fair across all three engines</li>
 *   <li>Pulsar only: CPU time of the {@code Pulsar-*} worker threads
 *       (ThreadMXBean), the async cost invisible to wall time</li>
 * </ul>
 *
 * <p>Protocol: create a FRESH world with a FIXED seed for every engine pass
 * (same seed → identical terrain → identical light work), then run
 * {@code /lightbench <radius> [warmupRadius]}. Re-running on a world that
 * already generated the bench region measures disk loads, not generation.
 */
@Mod(modid = Tags.ID, name = Tags.NAME, version = Tags.VERSION, acceptedMinecraftVersions = "[1.12.2]")
public class Lightbench {

    public static final Logger LOGGER = LogManager.getLogger(Tags.NAME);

    @Mod.EventHandler
    public void serverStarting(final FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandLightbench());
    }
}

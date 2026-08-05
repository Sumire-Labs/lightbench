package com.sumirelabs.lightbench;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Minecraft 1.12.2 port of the methodology of ca.spottedleaf/lightbench
 * (LGPL-3.0): generate deterministic fresh chunks and measure how long the
 * installed light engine takes to finish them.
 *
 * <p>The default {@code /lightbench gen} command warms up with a 101x101
 * region, then measures 36 separate 17x17 regions. It requests at most five
 * chunks per batch and waits for full light convergence after every batch.
 * This produces comparable end-to-end wall times despite the engines using
 * different execution models in 1.12.2:
 *
 * <ul>
 *   <li>vanilla performs lighting inline while generating chunks;</li>
 *   <li>Alfheim's deferred queue is explicitly flushed at each barrier;</li>
 *   <li>Pulsar's asynchronous queues are polled until queued and in-flight
 *       work has finished.</li>
 * </ul>
 *
 * <p>The headline metric is total wall time until every measured batch is
 * generated and fully lit. Batch and region distributions expose variance;
 * Pulsar worker CPU time is supplemental because parallel CPU seconds are not
 * interchangeable with elapsed wall time. {@code /lightbench bulk} remains a
 * separate whole-square throughput stress test.
 *
 * <p>Use a fresh world with the same fixed seed, JVM, configuration and mod
 * list for every engine pass. Before a generation run, the command rejects
 * any target or one-chunk-border coordinate that the provider reports as
 * already generated, without loading it. Reusing generated coordinates would
 * measure chunk loading rather than generation.
 *
 * <p>Completed generation runs write a versioned JSON report to the world's
 * {@code lightbench-results} directory. Raw batch data is recorded into
 * preallocated primitive arrays; environment collection, hashing,
 * serialization and file I/O happen only after measured phases finish.
 */
@Mod(modid = Tags.ID, name = Tags.NAME, version = Tags.VERSION, acceptedMinecraftVersions = "[1.12.2]")
public class Lightbench {

    public static final Logger LOGGER = LogManager.getLogger(Tags.NAME);

    @Mod.EventHandler
    public void serverStarting(final FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandLightbench());
    }

    /** Drives {@link TpsTest} and {@link SpikeTest}: brackets each server tick's work window. */
    @Mod.EventBusSubscriber(modid = Tags.ID)
    public static class TickHandler {

        @SubscribeEvent
        public static void onServerTick(final TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                TpsTest.onTickStart();
                SpikeTest.onTickStart();
            } else {
                TpsTest.onTickEnd();
                SpikeTest.onTickEnd();
            }
        }
    }
}

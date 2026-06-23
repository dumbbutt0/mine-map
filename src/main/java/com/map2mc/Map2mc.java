package com.map2mc;

import com.map2mc.build.BuildQueueManager;
import com.map2mc.command.Map2mcCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MapToMinecraft entrypoint.
 *
 * <p>The mod's single job is to lay landmark builds down as cinematic, bottom-up timelapses. It
 * owns block placement + ordering + pacing only — shaders (Iris), camera, and recording
 * (ReplayMod) are intentionally out of scope and done by the user in-game.
 *
 * <p>Phase 0 wired the mod + a {@code /map2mc ping} smoke command. Phase 1 adds the paced build
 * engine: the {@link BuildQueueManager} is driven from the end-of-server-tick event, and the
 * {@code /map2mc testbuild} command + run controls hang off the command tree.
 */
public class Map2mc implements ModInitializer {

    /** Mod id — used for the logger name, the command literal, and config paths. */
    public static final String MOD_ID = "map2mc";

    /** Minecraft version this build targets (surfaced by {@code /map2mc ping}). */
    public static final String MC_VERSION = "1.21.11";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[{}] initializing — cinematic bottom-up build engine (MC {})", MOD_ID, MC_VERSION);

        // The spine: tick the per-world build queues at the end of every server tick (so placements
        // settle before the next client sync), and clean up forced chunks when the server stops.
        ServerTickEvents.END_SERVER_TICK.register(BuildQueueManager::onEndServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(BuildQueueManager::onServerStopping);

        Map2mcCommands.register();
    }
}

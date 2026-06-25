package com.map2mc;

import com.map2mc.build.BuildQueueManager;
import com.map2mc.command.Map2mcCommands;
import com.map2mc.film.DirectorCamera;
import com.map2mc.orbit.LastBuild;
import com.map2mc.orbit.OrbitFlight;
import com.map2mc.preview.PreviewManager;
import com.map2mc.recording.RecordingManager;
import com.map2mc.recording.ReplayBridge;
import com.map2mc.undo.UndoManager;
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
        // Phase A: drive the frontier-tracking director camera AFTER the queue tick, so it reads the
        // build progress this tick produced. (Registration order = invocation order.)
        ServerTickEvents.END_SERVER_TICK.register(DirectorCamera::onEndServerTick);
        // Phase 2.5: drive the preview particle outline's expiry + re-emission on the same hook.
        ServerTickEvents.END_SERVER_TICK.register(PreviewManager::onEndServerTick);
        // Phase 3: advance any in-progress mod-driven orbit camera fly.
        ServerTickEvents.END_SERVER_TICK.register(OrbitFlight::onEndServerTick);
        // Auto-capture: watch a /map2mc record build and stop the ServerReplay capture when it finishes.
        // Registered AFTER the queue tick so it sees the build leave the queue on the completing tick.
        ServerTickEvents.END_SERVER_TICK.register(RecordingManager::onEndServerTick);
        // Once the server is up (ServerReplay's config exists by now), point its recordings at the
        // configured output dir (no-op unless recordOutputDir is set + ServerReplay is installed).
        ServerLifecycleEvents.SERVER_STARTED.register(ReplayBridge::applyOutputDir);
        ServerLifecycleEvents.SERVER_STOPPING.register(BuildQueueManager::onServerStopping);
        // Drop transient placement state when the server stops so it can't leak across world reloads.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PreviewManager.clearAll();
            UndoManager.clear();
            OrbitFlight.clearAll();
            DirectorCamera.clearAll();
            RecordingManager.clearAll(server);
            LastBuild.clear();
        });

        Map2mcCommands.register();
    }
}

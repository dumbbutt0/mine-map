package com.map2mc;

import com.map2mc.command.Map2mcCommands;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MapToMinecraft entrypoint.
 *
 * <p>The mod's single job is to lay landmark builds down as cinematic, bottom-up
 * timelapses. It owns block placement + ordering + pacing only — shaders (Iris),
 * camera, and recording (ReplayMod) are intentionally out of scope and done by the
 * user in-game. Phase 0 wires up the mod and a {@code /map2mc ping} smoke command;
 * the paced build engine (Phase 1) and the {@code .schem} reader (Phase 2) hang off
 * the command tree registered here.
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
        Map2mcCommands.register();
    }
}

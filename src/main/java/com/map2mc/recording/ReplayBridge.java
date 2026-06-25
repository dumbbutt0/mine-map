package com.map2mc.recording;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.map2mc.Map2mc;
import com.map2mc.Map2mcConfig;
import com.map2mc.place.Bounds;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Optional bridge to the external <b>ServerReplay</b> mod (senseiwells), driven purely by COMMAND
 * DISPATCH so map2mc needs no compile-time dependency on it and degrades to a no-op when it's absent.
 *
 * <p>ServerReplay records a chunk AREA <b>server-side</b> into a {@code .mcpr} (ReplayMod / Flashback
 * format) — no OBS, no live camera. map2mc's build runs on the server thread, so a chunk recording
 * captures it. We derive the chunk region from the build bounds, then dispatch:
 * <pre>
 *   replay start chunks around &lt;cx&gt; &lt;cz&gt; radius &lt;r&gt; in &lt;dim&gt; named &lt;name&gt;
 *   replay stop  chunks named &lt;name&gt; true
 * </pre>
 *
 * <p>All ServerReplay-specific strings live in the pure {@link #regionFor}/{@link #startCommand}/
 * {@link #stopCommand} methods so they're unit-tested and trivial to fix if the command syntax shifts
 * between ServerReplay versions (the one fragile coupling, kept isolated).
 */
public final class ReplayBridge {

    private ReplayBridge() {
    }

    /** A square chunk region: centre chunk {@code (cx,cz)} extending {@code radius} chunks each way. */
    public record ChunkRegion(int cx, int cz, int radius) {
    }

    /**
     * Pure: block bounds → the chunk square (the footprint plus {@code marginChunks} of surrounding
     * scenery) to record. The returned {@code radius} is sized so the square covers the whole chunk
     * extent; it is at least 1 (so even a one-chunk build records a sensible 3×3).
     */
    public static ChunkRegion regionFor(Bounds b, int marginChunks) {
        int m = Math.max(0, marginChunks);
        // blockCoord >> 4 == floorDiv(blockCoord, 16): the standard block→chunk mapping (handles <0).
        int cminX = (b.minX() >> 4) - m;
        int cmaxX = (b.maxX() >> 4) + m;
        int cminZ = (b.minZ() >> 4) - m;
        int cmaxZ = (b.maxZ() >> 4) + m;
        int cx = Math.floorDiv(cminX + cmaxX, 2);
        int cz = Math.floorDiv(cminZ + cmaxZ, 2);
        int radius = Math.max(1, Math.max(
                Math.max(cx - cminX, cmaxX - cx),
                Math.max(cz - cminZ, cmaxZ - cz)));
        return new ChunkRegion(cx, cz, radius);
    }

    /** Pure: the ServerReplay start command (no leading slash — the dispatcher expects none). */
    public static String startCommand(ChunkRegion r, String dimId, String name) {
        return String.format("replay start chunks around %d %d radius %d in %s named %s",
                r.cx(), r.cz(), r.radius(), dimId, name);
    }

    /** Pure: the ServerReplay stop command (save = true). */
    public static String stopCommand(String name) {
        return "replay stop chunks named " + name + " true";
    }

    /**
     * Is ServerReplay present? Id-agnostic — checks whether the {@code replay} command is registered,
     * which is robust to the mod's exact fabric id.
     */
    public static boolean available(MinecraftServer server) {
        return server.getCommandManager().getDispatcher().getRoot().getChild("replay") != null;
    }

    /**
     * If {@link Map2mcConfig#recordOutputDir} is set, ensure it exists and point ServerReplay's
     * {@code chunk_recording_path} at it (surgically patch only that one key in
     * {@code config/server-replay/config.json}, preserving the rest). Best-effort: logs and returns on
     * any problem. ServerReplay may cache its config at init, so a patch can need one game restart to
     * take effect. Call once at server start.
     */
    public static void applyOutputDir(MinecraftServer server) {
        String dir = Map2mcConfig.recordOutputDir;
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(Path.of(dir));
        } catch (Exception e) {
            Map2mc.LOGGER.warn("[map2mc][record] could not create recordOutputDir '{}': {}", dir, e.toString());
        }
        if (!available(server)) {
            return; // ServerReplay not installed → nothing to point
        }
        Path cfg = FabricLoader.getInstance().getGameDir()
                .resolve("config").resolve("server-replay").resolve("config.json");
        if (!Files.exists(cfg)) {
            Map2mc.LOGGER.info("[map2mc][record] ServerReplay config not present yet ({}); skipping path patch", cfg);
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(cfg)).getAsJsonObject();
            String key = "chunk_recording_path";
            String cur = root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : null;
            if (dir.equals(cur)) {
                return; // already pointed there
            }
            root.addProperty(key, dir);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(cfg, gson.toJson(root));
            Map2mc.LOGGER.info("[map2mc][record] pointed ServerReplay chunk_recording_path -> {} "
                    + "(restart once if ServerReplay cached its config)", dir);
        } catch (Exception e) {
            Map2mc.LOGGER.warn("[map2mc][record] could not patch ServerReplay config {}: {}", cfg, e.toString());
        }
    }

    /** Dispatch the start command at permission level 4. Returns false (and logs) on failure. */
    public static boolean start(MinecraftServer server, ServerWorld world, ChunkRegion r, String name) {
        String dim = world.getRegistryKey().getValue().toString();
        return dispatch(server, startCommand(r, dim, name));
    }

    /** Dispatch the stop+save command. Returns false (and logs) on failure. */
    public static boolean stop(MinecraftServer server, String name) {
        return dispatch(server, stopCommand(name));
    }

    private static boolean dispatch(MinecraftServer server, String cmd) {
        try {
            // withSilent: ServerReplay's own feedback goes to the log, not the player chat.
            server.getCommandManager().getDispatcher()
                    .execute(cmd, server.getCommandSource().withSilent());
            return true;
        } catch (CommandSyntaxException e) {
            Map2mc.LOGGER.warn("[map2mc][record] ServerReplay command failed ('{}'): {}", cmd, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            Map2mc.LOGGER.warn("[map2mc][record] ServerReplay command errored ('{}')", cmd, e);
            return false;
        }
    }
}

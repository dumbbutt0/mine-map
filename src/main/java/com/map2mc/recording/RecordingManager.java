package com.map2mc.recording;

import com.map2mc.Map2mc;
import com.map2mc.Map2mcConfig;
import com.map2mc.build.BuildQueueManager;
import com.map2mc.orbit.LastBuild;
import com.map2mc.orbit.Orbit;
import com.map2mc.orbit.OrbitExporter;
import com.map2mc.orbit.OrbitKeyframe;
import com.map2mc.place.Bounds;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Watches an in-progress {@code /map2mc record} build and, when it finishes, stops the ServerReplay
 * capture and writes the orbit keyframe table. Tick-driven on {@code END_SERVER_TICK}, mirroring
 * {@link com.map2mc.orbit.OrbitFlight} / {@link com.map2mc.film.DirectorCamera}. One recording per world.
 *
 * <p>The recording itself is server-side (ServerReplay captures the chunk area); this class never
 * touches the player camera — the user sets the camera later in ReplayMod from the exported table.
 * If ServerReplay is absent ({@code capture == false}) the build is still tracked so the orbit table
 * is exported on completion; only the {@code .mcpr} capture is skipped.
 */
public final class RecordingManager {

    /** Ticks to keep recording AFTER the build leaves the queue, so the final placements' block-update
     *  packets are flushed into the .mcpr (else the top layer can be missing). 4 ticks = 0.2s. */
    private static final int STOP_GRACE_TICKS = 4;

    private static final class Session {
        final long buildId;
        final String name;
        final Bounds bounds;
        final int buildSeconds;
        final String label;
        final UUID player;
        final boolean capture;
        int graceLeft = -1; // -1 = build still running; >=0 = counting down to stop

        Session(long buildId, String name, Bounds bounds, int buildSeconds,
                String label, UUID player, boolean capture) {
            this.buildId = buildId;
            this.name = name;
            this.bounds = bounds;
            this.buildSeconds = buildSeconds;
            this.label = label;
            this.player = player;
            this.capture = capture;
        }
    }

    private static final Map<RegistryKey<World>, Session> ACTIVE = new HashMap<>();

    private RecordingManager() {
    }

    /**
     * Begin a recording for a just-submitted build. Fires the ServerReplay start command if the mod is
     * present. {@code player} may be null (console source). Supersedes any active recording in the world.
     */
    public static void start(ServerWorld world, MinecraftServer server, long buildId, String name,
                             Bounds bounds, int buildSeconds, String label, UUID player,
                             ReplayBridge.ChunkRegion region) {
        finish(server, ACTIVE.remove(world.getRegistryKey()), "superseded");
        boolean capture = ReplayBridge.available(server);
        if (capture) {
            ReplayBridge.start(server, world, region, name);
        }
        ACTIVE.put(world.getRegistryKey(),
                new Session(buildId, name, bounds, buildSeconds, label, player, capture));
    }

    public static void onEndServerTick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<RegistryKey<World>, Session>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<RegistryKey<World>, Session> e = it.next();
            ServerWorld world = server.getWorld(e.getKey());
            Session s = e.getValue();
            if (s.graceLeft < 0) {
                // The build job leaves the queue when it COMPLETES or is CANCELLED — done either way.
                boolean stillBuilding = world != null
                        && BuildQueueManager.get(world).byId(s.buildId) != null;
                if (!stillBuilding) {
                    s.graceLeft = STOP_GRACE_TICKS; // keep recording briefly so the last blocks land
                }
            } else if (s.graceLeft == 0) {
                finish(server, s, "complete");
                it.remove();
            } else {
                s.graceLeft--;
            }
        }
    }

    /** Manual stop ({@code /map2mc record stop}): close the active recording in this world (build continues). */
    public static boolean stop(ServerWorld world, MinecraftServer server) {
        Session s = ACTIVE.remove(world.getRegistryKey());
        if (s == null) {
            return false;
        }
        finish(server, s, "stopped");
        return true;
    }

    public static boolean isActive(ServerWorld world) {
        return ACTIVE.containsKey(world.getRegistryKey());
    }

    /** Stop + save any active recordings (server shutting down — don't lose the take). */
    public static void clearAll(MinecraftServer server) {
        for (Session s : ACTIVE.values()) {
            finish(server, s, "server stopping");
        }
        ACTIVE.clear();
    }

    private static void finish(MinecraftServer server, Session s, String why) {
        if (s == null) {
            return;
        }
        if (s.capture) {
            ReplayBridge.stop(server, s.name);
        }
        Path table = exportTable(s);
        String msg = s.capture
                ? String.format("map2mc: recording '%s' %s — .mcpr saved under recordings/chunks/%s/. "
                        + "Open it in ReplayMod, apply the camera path (%s), render.",
                s.label, why, s.name, table != null ? table.getFileName() : "table export failed")
                : String.format("map2mc: build '%s' %s (no ServerReplay capture). Orbit table: %s",
                s.label, why, table != null ? table : "export failed");
        Map2mc.LOGGER.info("[map2mc][record] {}", msg);
        ServerPlayerEntity p = s.player == null ? null : server.getPlayerManager().getPlayer(s.player);
        if (p != null) {
            p.sendMessage(Text.literal(msg), false);
        }
    }

    /** Build the slow orbit around the finished build and write the human-enterable keyframe table. */
    private static Path exportTable(Session s) {
        try {
            Orbit.Params pr = Orbit.Params.fromBounds(s.bounds, -1, Map2mcConfig.orbitRadiusFactor);
            long renderMs = Map2mcConfig.orbitRenderSeconds * 1000L;
            long buildMs = s.buildSeconds * 1000L;
            List<OrbitKeyframe> kfs = Orbit.compute(pr.cx(), pr.cz(), pr.aimY(), pr.radius(),
                    pr.camYStart(), pr.camYEnd(), Map2mcConfig.orbitKeyframes, renderMs, buildMs);
            LastBuild.Info info = new LastBuild.Info(s.bounds, s.buildSeconds, s.label);
            String outDir = Map2mcConfig.recordOutputDir;
            Path dir = outDir == null || outDir.isBlank() ? null : Path.of(outDir);
            return OrbitExporter.export(kfs, pr, Map2mcConfig.orbitRenderSeconds, info, dir);
        } catch (IOException ex) {
            Map2mc.LOGGER.error("[map2mc][record] orbit table export failed", ex);
            return null;
        }
    }
}

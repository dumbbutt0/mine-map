package com.map2mc.preview;

import com.map2mc.place.Bounds;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a temporary particle outline of where a build WOULD land, without placing any blocks. One
 * active preview per world; re-running {@code /map2mc preview …} moves it, and it auto-expires (or
 * {@code /map2mc preview clear} removes it immediately).
 *
 * <p>Server-side {@code spawnParticles} (forced/long-distance) means every nearby client sees the
 * outline with no client mod. Particles are momentary, so the outline is re-emitted every
 * {@link #EMIT_INTERVAL_TICKS} ticks from the end-of-tick hook until it expires.
 */
public final class PreviewManager {

    /** Re-emit cadence — often enough to look solid, sparse enough to stay cheap. */
    private static final int EMIT_INTERVAL_TICKS = 5;

    /** Default lifetime if a caller doesn't specify one (~30s at 20 TPS). */
    public static final int DEFAULT_DURATION_TICKS = 600;

    /** Spacing between outline particles along each edge (blocks). */
    private static final double EDGE_STEP = 1.0;

    private static final Map<RegistryKey<World>, Preview> PREVIEWS = new HashMap<>();

    private PreviewManager() {
    }

    private static final class Preview {
        final ServerWorld world;
        final Bounds bounds;
        int remainingTicks;
        int sinceEmit;

        Preview(ServerWorld world, Bounds bounds, int durationTicks) {
            this.world = world;
            this.bounds = bounds;
            this.remainingTicks = durationTicks;
        }
    }

    /** Show (or replace) the outline for {@code bounds} in {@code world} for {@code durationTicks}. */
    public static void show(ServerWorld world, Bounds bounds, int durationTicks) {
        PREVIEWS.put(world.getRegistryKey(), new Preview(world, bounds, Math.max(1, durationTicks)));
        emit(PREVIEWS.get(world.getRegistryKey())); // draw immediately so it appears on the same tick
    }

    /** Remove the active outline in {@code world}; returns true if there was one. */
    public static boolean clear(ServerWorld world) {
        return PREVIEWS.remove(world.getRegistryKey()) != null;
    }

    public static boolean hasActive(ServerWorld world) {
        return PREVIEWS.containsKey(world.getRegistryKey());
    }

    /** Drive expiry + periodic re-emission. Registered on END_SERVER_TICK. */
    public static void onEndServerTick(MinecraftServer server) {
        if (PREVIEWS.isEmpty()) {
            return;
        }
        PREVIEWS.values().removeIf(p -> {
            if (--p.remainingTicks <= 0) {
                return true; // expired -> drop
            }
            if (++p.sinceEmit % EMIT_INTERVAL_TICKS == 0) {
                emit(p);
            }
            return false;
        });
    }

    public static void clearAll() {
        PREVIEWS.clear();
    }

    private static void emit(Preview p) {
        List<Vec3d> pts = p.bounds.edgeSamples(EDGE_STEP);
        for (Vec3d pt : pts) {
            // force=true (long distance) + important=true so tall outlines are visible from afar.
            p.world.spawnParticles(ParticleTypes.END_ROD, true, true,
                    pt.x, pt.y, pt.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}

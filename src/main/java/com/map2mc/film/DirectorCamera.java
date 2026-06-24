package com.map2mc.film;

import com.map2mc.Map2mc;
import com.map2mc.Map2mcConfig;
import com.map2mc.build.BuildJob;
import com.map2mc.orbit.Orbit;
import com.map2mc.orbit.OrbitKeyframe;
import com.map2mc.place.Bounds;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side director (generalizes {@link com.map2mc.orbit.OrbitFlight}): each
 * {@code END_SERVER_TICK} it computes the camera pose from the active build's PROGRESS + frontier
 * (via {@link FilmDirector}), and — when a player is filming — teleports that spectator there and
 * publishes prev+cur pose to {@link DirectorState} for the client mixin to smooth per render frame.
 *
 * <p>Keyed by world (one film per world at a time). The player is OPTIONAL: a console/dedicated-server
 * {@code film} runs in LOG-ONLY mode (compute + log the pose sequence, no teleport/publish), which is
 * what the headless gate asserts on. The per-tick teleport (when a player is present) keeps the
 * looked-at chunks loaded and the camera entity near the pose, so the client mixin's per-frame
 * override never strays far from loaded geometry.
 */
public final class DirectorCamera {

    private static final class Film {
        final UUID player;            // null = headless / console (log-only, no camera)
        final ServerWorld world;
        final RegistryKey<World> worldKey;
        final BuildJob job;
        final Bounds fullBounds;      // the whole build's bounds; framing is sized to CURRENT extent
        final int radiusOverride;     // >0 pins the orbit radius; <=0 = dynamic current-extent framing
        final int window;
        final Vec3d fallbackAim;
        final GameMode prevMode;      // only meaningful when player != null
        final long buildMs;
        DirectorState.Pose lastPose;
        double maxFrontierY = Double.NEGATIVE_INFINITY; // monotonic clamp so the camera never dips
        int logTicks = 0;

        Film(UUID player, ServerWorld world, BuildJob job, Bounds fullBounds, int radiusOverride,
             int window, Vec3d fallbackAim, GameMode prevMode, long buildMs) {
            this.player = player;
            this.world = world;
            this.worldKey = world.getRegistryKey();
            this.job = job;
            this.fullBounds = fullBounds;
            this.radiusOverride = radiusOverride;
            this.window = window;
            this.fallbackAim = fallbackAim;
            this.prevMode = prevMode;
            this.buildMs = buildMs;
        }
    }

    private static final Map<RegistryKey<World>, Film> ACTIVE = new HashMap<>();

    private DirectorCamera() {
    }

    /**
     * Begin filming {@code job} in {@code world}. If {@code player} is non-null it is switched to
     * spectator and its camera is driven (live fly); if null, the director only logs the pose
     * sequence (headless verification). Cuts to the progress-0 pose immediately.
     */
    public static void start(ServerWorld world, ServerPlayerEntity player, BuildJob job,
                             Bounds fullBounds, int radiusOverride, int window, Vec3d fallbackAim,
                             int durationSecs) {
        RegistryKey<World> key = world.getRegistryKey();
        Film existing = ACTIVE.remove(key);
        UUID pid = null;
        GameMode prev = GameMode.SPECTATOR;
        if (player != null) {
            pid = player.getUuid();
            prev = existing != null && existing.player != null
                    ? existing.prevMode : player.interactionManager.getGameMode();
            player.changeGameMode(GameMode.SPECTATOR);
        }
        Film f = new Film(pid, world, job, fullBounds, radiusOverride, Math.max(1, window), fallbackAim,
                prev, Math.max(1, durationSecs) * 1000L);
        ACTIVE.put(key, f);
        tickFilm(player, f); // cut to the start pose
    }

    /** Stop filming in {@code world}; restore gamemode if a player was flying. */
    public static boolean stop(ServerWorld world, MinecraftServer server) {
        Film f = ACTIVE.remove(world.getRegistryKey());
        if (f == null) {
            return false;
        }
        if (f.player != null && server != null) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(f.player);
            if (p != null) {
                p.changeGameMode(f.prevMode);
            }
        }
        if (ACTIVE.isEmpty()) {
            DirectorState.reset();
        }
        return true;
    }

    public static void onEndServerTick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        Iterator<Film> it = ACTIVE.values().iterator();
        while (it.hasNext()) {
            Film f = it.next();
            ServerPlayerEntity p = f.player == null ? null : server.getPlayerManager().getPlayer(f.player);
            if (f.player != null && p == null) {
                it.remove(); // the flying player logged off
                continue;
            }
            tickFilm(p, f);
            if (f.job.isDone()) {
                if (p != null) {
                    p.changeGameMode(f.prevMode); // the shot ends exactly when the build does
                }
                Map2mc.LOGGER.info("[map2mc][director] film complete: {}", f.job.label());
                it.remove();
            }
        }
        if (ACTIVE.isEmpty()) {
            DirectorState.reset();
        }
    }

    /** Compute the pose for this tick; teleport + publish if a player is flying; always log. */
    private static void tickFilm(ServerPlayerEntity player, Film f) {
        float progress = f.job.progress();

        // Frontier with a monotonic-Y clamp so the camera climbs steadily even if the sliding
        // window's centroid dips between layers of differing width.
        Frontier raw = Frontier.fromJob(f.job, f.window, f.fallbackAim);
        double clampedY = Math.max(raw.centroid().y, f.maxFrontierY);
        f.maxFrontierY = clampedY;
        Frontier frontier = new Frontier(
                new Vec3d(raw.centroid().x, clampedY, raw.centroid().z), raw.bbox(), raw.count());

        // Dynamic framing: size the orbit to the CURRENT built extent (tight on the small base,
        // widening as the structure grows) instead of the finished build's final size.
        Orbit.Params params = FilmDirector.framingFor(f.fullBounds, clampedY, f.radiusOverride,
                Map2mcConfig.orbitRadiusFactor);
        OrbitKeyframe k = FilmDirector.directorPose(progress, frontier, params,
                Map2mcConfig.filmOrbitTurns, Map2mcConfig.filmCameraRise, Map2mcConfig.filmAimLeadY,
                0L, f.buildMs);

        DirectorState.Pose cur = new DirectorState.Pose(k.x(), k.y(), k.z(), k.yaw(), k.pitch());
        DirectorState.Pose prev = f.lastPose != null ? f.lastPose : cur;
        f.lastPose = cur;

        if (player != null) {
            // Coarse 20Hz move (chunk loading + base pose); the client mixin smooths prev→cur per frame.
            player.teleport(f.world, cur.x(), cur.y(), cur.z(),
                    EnumSet.noneOf(PositionFlag.class), cur.yaw(), cur.pitch(), false);
            DirectorState.publish(new DirectorState.Snapshot(prev, cur, f.player, f.worldKey));
        }

        if (f.logTicks++ % 10 == 0 || f.job.isDone()) {
            double aimY = clampedY + Map2mcConfig.filmAimLeadY;
            Map2mc.LOGGER.info(
                    "[map2mc][director] progress={} placed={}/{} frontierY={} count={} radius={} "
                            + "camPose=({},{},{}) yaw={} pitch={} aim=({},{},{})",
                    String.format("%.3f", progress), f.job.placed(), f.job.total(),
                    String.format("%.1f", clampedY), frontier.count(),
                    String.format("%.1f", params.radius()),
                    String.format("%.2f", cur.x()), String.format("%.2f", cur.y()), String.format("%.2f", cur.z()),
                    String.format("%.2f", cur.yaw()), String.format("%.2f", cur.pitch()),
                    String.format("%.2f", params.cx()), String.format("%.2f", aimY),
                    String.format("%.2f", params.cz()));
        }
    }

    public static void clearAll() {
        ACTIVE.clear();
        DirectorState.reset();
    }
}

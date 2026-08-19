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
        final Vec3d startPos;         // player pose at film start — restored on stop/complete (no fall death)
        final float startYaw;
        final float startPitch;
        final long buildMs;
        final double handoffProgress; // area-profile aerial→orbit cut point; <0 = defer to height
        final StructureProfile.AspectClass structureClass; // shape → which shot is used
        DirectorState.Pose lastPose;
        double maxFrontierY = Double.NEGATIVE_INFINITY; // monotonic clamp so the camera never dips
        boolean cutToOrbit = false;   // fired once when the aerial→orbit handoff is crossed
        int logTicks = 0;

        Film(UUID player, ServerWorld world, BuildJob job, Bounds fullBounds, int radiusOverride,
             int window, Vec3d fallbackAim, GameMode prevMode, Vec3d startPos, float startYaw,
             float startPitch, long buildMs, double handoffProgress,
             StructureProfile.AspectClass structureClass) {
            this.player = player;
            this.world = world;
            this.worldKey = world.getRegistryKey();
            this.job = job;
            this.fullBounds = fullBounds;
            this.radiusOverride = radiusOverride;
            this.window = window;
            this.fallbackAim = fallbackAim;
            this.prevMode = prevMode;
            this.startPos = startPos;
            this.startYaw = startYaw;
            this.startPitch = startPitch;
            this.buildMs = buildMs;
            this.handoffProgress = handoffProgress;
            this.structureClass = structureClass;
        }
    }

    private static final Map<RegistryKey<World>, Film> ACTIVE = new HashMap<>();

    /** Blocks the aerial camera is kept above the current build top (so it always looks DOWN on it). */
    private static final double AERIAL_CLEARANCE = 8.0;

    private DirectorCamera() {
    }

    /**
     * Begin filming {@code job} in {@code world}. If {@code player} is non-null it is switched to
     * spectator and its camera is driven (live fly); if null, the director only logs the pose
     * sequence (headless verification). Cuts to the progress-0 pose immediately.
     */
    public static void start(ServerWorld world, ServerPlayerEntity player, BuildJob job,
                             Bounds fullBounds, int radiusOverride, int window, Vec3d fallbackAim,
                             int durationSecs, double handoffProgress,
                             StructureProfile.AspectClass structureClass) {
        RegistryKey<World> key = world.getRegistryKey();
        Film existing = ACTIVE.remove(key);
        UUID pid = null;
        GameMode prev = GameMode.SPECTATOR;
        Vec3d startPos = null;
        float startYaw = 0f;
        float startPitch = 0f;
        if (player != null) {
            pid = player.getUuid();
            // Remember the player's pre-film pose so restoring their gamemode on stop/complete returns
            // them to the GROUND instead of dropping them from the camera's altitude (the fall-death
            // bug). On a re-film, keep the ORIGINAL pose (don't capture the spectator camera position).
            boolean carry = existing != null && existing.player != null;
            prev = carry ? existing.prevMode : player.interactionManager.getGameMode();
            startPos = carry ? existing.startPos : player.getEntityPos();
            startYaw = carry ? existing.startYaw : player.getYaw();
            startPitch = carry ? existing.startPitch : player.getPitch();
            player.changeGameMode(GameMode.SPECTATOR);
        }
        Film f = new Film(pid, world, job, fullBounds, radiusOverride, Math.max(1, window), fallbackAim,
                prev, startPos, startYaw, startPitch, Math.max(1, durationSecs) * 1000L, handoffProgress,
                structureClass);
        ACTIVE.put(key, f);
        tickFilm(player, f); // cut to the start pose
    }

    /** Stop filming in {@code world}; safely return the flying player and restore their gamemode. */
    public static boolean stop(ServerWorld world, MinecraftServer server) {
        Film f = ACTIVE.remove(world.getRegistryKey());
        if (f == null) {
            return false;
        }
        if (f.player != null && server != null) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(f.player);
            if (p != null) {
                restorePlayer(p, f);
            }
        }
        if (ACTIVE.isEmpty()) {
            DirectorState.reset();
        }
        return true;
    }

    /**
     * End the shot for {@code p} by returning them to their PRE-FILM gamemode (default), so they're not
     * stranded in spectator (which can't land on the ground). For a fall-safe prior mode (CREATIVE /
     * SPECTATOR — no fall damage) the gamemode is restored IN PLACE at the final camera pose, so they
     * can simply fly/descend down. For a fall-damage-able prior mode (SURVIVAL / ADVENTURE) they are
     * first teleported back to their pre-film GROUND spot, so restoring at the camera's altitude isn't a
     * lethal fall. Opt-in {@code filmEndStaysSpectator} instead leaves them floating in spectator to
     * finish the shot by hand (they {@code /gamemode} back themselves).
     */
    private static void restorePlayer(ServerPlayerEntity p, Film f) {
        if (Map2mcConfig.filmEndStaysSpectator) {
            return; // opt-in: leave the player in spectator at the final camera pose
        }
        boolean fallSafe = f.prevMode == GameMode.CREATIVE || f.prevMode == GameMode.SPECTATOR;
        if (!fallSafe && f.startPos != null) {
            // survival/adventure: land them at the pre-film ground spot before restoring (no fall death)
            p.teleport(f.world, f.startPos.x, f.startPos.y, f.startPos.z,
                    EnumSet.noneOf(PositionFlag.class), f.startYaw, f.startPitch, false);
            p.setOnGround(true);
        }
        p.fallDistance = 0;
        p.changeGameMode(f.prevMode); // creative restored in place → they can fly back down and land
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
                    restorePlayer(p, f); // the shot ends exactly when the build does — land them safely
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

        // Frame off the TRUE current top — the window's max Y (in ASCENDING_Y order the last-placed
        // blocks are the top band), monotonically clamped so the framing never dips. Using the top
        // (not the lagging centroid) keeps the whole built height INSIDE the vertical frame instead of
        // clipping the upper tower once the radius would otherwise lag.
        Frontier raw = Frontier.fromJob(f.job, f.window, f.fallbackAim);
        double clampedTop = Math.max(raw.bbox().maxY() + 1.0, f.maxFrontierY);
        f.maxFrontierY = clampedTop;

        double cx = (f.fullBounds.minX() + f.fullBounds.maxX() + 1) / 2.0;
        double cz = (f.fullBounds.minZ() + f.fullBounds.maxZ() + 1) / 2.0;
        double baseY = f.fullBounds.minY();
        double finalHeight = Math.max(1.0, f.fullBounds.maxY() - baseY);
        double currentHeight = clampedTop - baseY;
        double heightThreshold = Map2mcConfig.filmAerialUntilHeightFrac * finalHeight;

        // Handoff aerial→orbit only once BOTH are true: the build has NARROWED past its wide base
        // (structural — the area-profile handoffProgress; so we never cut mid-floor) AND it has RISEN
        // enough that there's something to look UP at (height). For a thin-foundation build like the
        // Eiffel the height drives it; for a thick base the structural check holds the overhead until
        // the floor is done. (handoffProgress < 0 = no clear transition → height alone.) Both monotonic
        // → the cut fires exactly once.
        boolean structuralPassed = f.handoffProgress < 0 || progress >= f.handoffProgress;
        boolean heightReached = currentHeight >= heightThreshold;
        boolean aerial = Map2mcConfig.filmAerialEnabled && !(structuralPassed && heightReached);

        final OrbitKeyframe k;
        final String phase;
        final double logRadius;
        final double logAimY;
        if (f.structureClass == StructureProfile.AspectClass.LINEAR) {
            // Shape-driven shot: a tracking dolly along the long axis (a circling orbit is wrong for a
            // long, thin build). One smooth sweep across the build's length over the whole timeline.
            k = FilmDirector.trackingPose(f.fullBounds, progress,
                    Map2mcConfig.filmAspectW, Map2mcConfig.filmAspectH, Map2mcConfig.filmBaseFovDeg,
                    Map2mcConfig.filmHeadroomFrac, Map2mcConfig.filmFrameMargin,
                    Map2mcConfig.filmHeroCamFrac, 0L, f.buildMs);
            phase = "tracking";
            logRadius = Math.hypot(k.x() - cx, k.z() - cz);
            logAimY = baseY + 0.5 * finalHeight;
        } else if (aerial) {
            // Low phase: a high-oblique aerial shot looking DOWN at the footprint (the legs/platform
            // pattern is the subject), drifting its azimuth slowly across the phase.
            double aerialFrac = Map2mcConfig.filmAerialTurns
                    * Math.min(1.0, Math.max(0.0, currentHeight / Math.max(1.0, heightThreshold)));
            k = FilmDirector.aerialPose(f.fullBounds, aerialFrac,
                    Map2mcConfig.filmAspectW, Map2mcConfig.filmAspectH, Map2mcConfig.filmBaseFovDeg,
                    Map2mcConfig.filmAerialPitchDeg, Map2mcConfig.filmAerialMargin,
                    clampedTop, AERIAL_CLEARANCE, Map2mcConfig.filmCloudCeilingY, 0L, f.buildMs);
            phase = "aerial";
            logRadius = Math.hypot(k.x() - cx, k.z() - cz);
            logAimY = baseY;
        } else {
            // First tick at/after the threshold: CUT (instant jump, no lerp) from the aerial shot to
            // the rising orbit. currentHeight is monotonic, so this fires exactly once.
            if (!f.cutToOrbit) {
                f.lastPose = null;
                f.cutToOrbit = true;
            }
            // V2-1 aspect-aware vertical + hero framing: the camera distance fills the configured target
            // frame, low + aiming high so the structure looms; distance grows as the build climbs.
            Orbit.Params params = FilmDirector.framingForAspect(f.fullBounds, clampedTop, f.radiusOverride,
                    Map2mcConfig.filmAspectW, Map2mcConfig.filmAspectH, Map2mcConfig.filmBaseFovDeg,
                    Map2mcConfig.filmHeadroomFrac, Map2mcConfig.filmFrameMargin,
                    Map2mcConfig.filmHeroCamFrac, Map2mcConfig.filmHeroAimFrac);
            double frac = progress * Math.max(1, Map2mcConfig.filmOrbitTurns);
            k = Orbit.poseAt(params.cx(), params.cz(), params.aimY(), params.radius(),
                    params.camYStart(), params.camYEnd(), frac, 0L, f.buildMs);
            phase = "orbit";
            logRadius = params.radius();
            logAimY = params.aimY();
        }

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
            Map2mc.LOGGER.info(
                    "[map2mc][director] phase={} hp={} progress={} placed={}/{} topY={} count={} radius={} "
                            + "camPose=({},{},{}) yaw={} pitch={} aim=({},{},{})",
                    phase, String.format("%.3f", f.handoffProgress),
                    String.format("%.3f", progress), f.job.placed(), f.job.total(),
                    String.format("%.1f", clampedTop), raw.count(),
                    String.format("%.1f", logRadius),
                    String.format("%.2f", cur.x()), String.format("%.2f", cur.y()), String.format("%.2f", cur.z()),
                    String.format("%.2f", cur.yaw()), String.format("%.2f", cur.pitch()),
                    String.format("%.2f", cx), String.format("%.2f", logAimY),
                    String.format("%.2f", cz));
        }
    }

    public static void clearAll() {
        ACTIVE.clear();
        DirectorState.reset();
    }
}

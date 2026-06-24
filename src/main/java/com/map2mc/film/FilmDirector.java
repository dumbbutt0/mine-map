package com.map2mc.film;

import com.map2mc.orbit.Orbit;
import com.map2mc.orbit.OrbitKeyframe;
import net.minecraft.util.math.Vec3d;

/**
 * Pure director-camera pose math (unit-tested, no Minecraft runtime) — the testable core of Phase A.
 *
 * <p>The camera orbits the build's horizontal centre at a fixed radius (from {@link Orbit.Params}),
 * but it TRACKS the construction {@link Frontier}: its height stays a fixed rise above the frontier
 * centroid and it aims at the frontier, so as the build climbs the camera climbs with it. The pose
 * is a function of build PROGRESS only ({@code placed/total}) — the deterministic clock shared by
 * live fly and (Phase B) offline export. If the build hitches, progress doesn't advance, so the
 * camera waits.
 */
public final class FilmDirector {

    private FilmDirector() {
    }

    /**
     * @param progress    build progress in [0,1] (the ONLY clock)
     * @param frontier    the construction front (aim target)
     * @param params      orbit framing (cx, cz, radius) — derived once from the build's bounds
     * @param orbitTurns  full revolutions across the whole build (≥1)
     * @param cameraRise  blocks the camera sits above the frontier centroid
     * @param aimLeadY    blocks above the frontier centroid the camera aims
     * @param renderMs    informational render-timeline length (for Phase B export; 0 for live)
     * @param buildMs     informational build-timeline length (for Phase B export; 0 for live)
     */
    public static OrbitKeyframe directorPose(float progress, Frontier frontier, Orbit.Params params,
                                             int orbitTurns, double cameraRise, double aimLeadY,
                                             long renderMs, long buildMs) {
        Vec3d c = frontier.centroid();
        double camY = c.y + cameraRise;   // tracks the front (DirectorCamera additionally clamps non-decreasing)
        double aimY = c.y + aimLeadY;
        double frac = progress * Math.max(1, orbitTurns);
        // Orbit XZ around the build centre (flat camY via start==end); AIM at the frontier height.
        return Orbit.poseAtAiming(params.cx(), params.cz(), params.radius(), camY, camY, frac,
                params.cx(), aimY, params.cz(), renderMs, buildMs);
    }
}

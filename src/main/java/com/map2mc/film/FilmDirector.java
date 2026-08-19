package com.map2mc.film;

import com.map2mc.orbit.Orbit;
import com.map2mc.orbit.OrbitKeyframe;
import com.map2mc.place.Bounds;
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
     * Framing ({@link Orbit.Params}: centre + radius) sized to the build's CURRENT extent, so the
     * camera stays tight on the small base and widens as the structure grows — rather than being
     * locked to the finished build's size (the "circling a speck in the fog" problem). Pure.
     *
     * <p>Uses the full footprint XZ (the orbit centre is the build centre, ~constant) but only the
     * built height so far: {@code baseY .. currentTopY}. So {@code radius = horizontalRadius·factor +
     * span·0.25} grows from footprint-sized (low base) to full as height accrues. A positive
     * {@code radiusOverride} pins the radius (the user asked for a fixed framing).
     *
     * <p>ASSUMPTION: the footprint is widest at/near the base (true for towers/landmarks), so the
     * full XZ footprint is a good stand-in for "what's built so far" horizontally. For shapes that
     * widen higher up, this over-frames slightly early (never too tight) — acceptable; an exact
     * cumulative-bbox tracker is the fallback if ever needed.
     */
    public static Orbit.Params framingFor(Bounds fullBounds, double currentTopY,
                                          int radiusOverride, double radiusFactor) {
        if (radiusOverride > 0) {
            return Orbit.Params.fromBounds(fullBounds, radiusOverride, radiusFactor);
        }
        int top = (int) Math.ceil(Math.max(currentTopY, fullBounds.minY()));
        Bounds current = new Bounds(fullBounds.minX(), fullBounds.minY(), fullBounds.minZ(),
                fullBounds.maxX(), top, fullBounds.maxZ());
        return Orbit.Params.fromBounds(current, 0, radiusFactor);
    }

    /**
     * Aspect-aware framing (V2-1): sizes the camera distance so the build's CURRENT extent fills a
     * target frame of aspect {@code aspectW:aspectH} at a base vertical FOV, and places the camera LOW
     * with the aim HIGH so the structure looms (a "hero" up-look). Pure. Returned as an
     * {@link Orbit.Params} so {@link Orbit#poseAt} drives it directly (aimY = the look height;
     * camYStart == camYEnd = a flat camera height for this tick — it climbs across ticks as the build
     * grows, via {@code currentTopY}).
     *
     * <p>The mod can't resize the game window, so {@code aspectW:aspectH} is a CONFIGURED target the
     * user records at (a portrait window, or ReplayMod 1080×1920) — not the dev window;
     * {@code baseFovDeg} is the vertical FOV the math assumes (tune to match the recording).
     *
     * <ul>
     *   <li>{@code dV = (span/2 + headroomFrac·span) / tan(vFov/2)} — distance to fit the HEIGHT.</li>
     *   <li>{@code dW = horizontalRadius / tan(hFov/2)}, with {@code tan(hFov/2) = (W/H)·tan(vFov/2)} —
     *       distance to fit the worst-case rotated WIDTH.</li>
     *   <li>{@code radius = max(dV, dW)·margin}. For a tall tower in 9:16 {@code dV} binds (frame by
     *       height with headroom); for a wide build in 16:9 {@code dW} binds.</li>
     * </ul>
     * A positive {@code radiusOverride} pins the distance (skips the aspect math).
     */
    public static Orbit.Params framingForAspect(Bounds fullBounds, double currentTopY, int radiusOverride,
                                                int aspectW, int aspectH, double baseFovDeg,
                                                double headroomFrac, double margin,
                                                double heroCamFrac, double heroAimFrac) {
        double cx = (fullBounds.minX() + fullBounds.maxX() + 1) / 2.0;
        double cz = (fullBounds.minZ() + fullBounds.maxZ() + 1) / 2.0;
        double baseY = fullBounds.minY();
        double top = Math.max(currentTopY, baseY + 1.0);
        double span = Math.max(1.0, top - baseY);
        double horizRadius = Math.max(1.0, fullBounds.horizontalRadius());

        double radius;
        if (radiusOverride > 0) {
            radius = radiusOverride;
        } else {
            double tanV = Math.tan(Math.toRadians(baseFovDeg) / 2.0);
            double tanH = ((double) aspectW / aspectH) * tanV;
            double dV = (span * 0.5 + headroomFrac * span) / tanV; // fit the height into the vertical
            double dW = horizRadius / tanH;                        // fit the rotated width into the horizontal
            radius = Math.max(4.0, Math.max(dV, dW) * margin);
        }
        double camY = baseY + heroCamFrac * span;
        double aimY = baseY + heroAimFrac * span;
        return new Orbit.Params(cx, cz, aimY, radius, camY, camY);
    }

    /**
     * High-oblique AERIAL establishing shot for the LOW-height phase: looks DOWN at the footprint (the
     * leg/platform pattern is the subject) instead of seeing a wide-flat build as a distant strip on
     * grass. Pure. Reuses the V2-1 width branch to frame the footprint, then reuses {@link Orbit#poseAt}.
     *
     * <ul>
     *   <li>{@code camRadius = (horizontalRadius / tan(hFov/2))·margin} — the HORIZONTAL distance that
     *       frames the footprint width (same as the V2-1 {@code dW} term).</li>
     *   <li>{@code camY = max(currentTop + clearance, min(baseY + camRadius·tan(pitchDeg), cloudCeiling))}
     *       — the pitch sets the high-oblique elevation, capped below the cloud layer so the start isn't
     *       lost in cloud, but never below the build top so it always looks DOWN on the build.</li>
     *   <li>Aim at the footprint centre at base level {@code (cx, baseY, cz)} → a downward look.</li>
     *   <li>{@code frac} rotates the azimuth slowly across the aerial phase (caller scales by turns).</li>
     * </ul>
     */
    public static OrbitKeyframe aerialPose(Bounds fullBounds, double frac,
                                           int aspectW, int aspectH, double baseFovDeg, double pitchDeg,
                                           double margin, double currentTop, double clearance,
                                           double cloudCeiling, long renderMs, long buildMs) {
        double cx = (fullBounds.minX() + fullBounds.maxX() + 1) / 2.0;
        double cz = (fullBounds.minZ() + fullBounds.maxZ() + 1) / 2.0;
        double baseY = fullBounds.minY();
        double horizRadius = Math.max(1.0, fullBounds.horizontalRadius());

        double tanV = Math.tan(Math.toRadians(baseFovDeg) / 2.0);
        double tanH = ((double) aspectW / aspectH) * tanV;
        double camRadius = Math.max(4.0, (horizRadius / tanH) * margin);
        double camYByPitch = baseY + camRadius * Math.tan(Math.toRadians(pitchDeg));
        // Cap the pitch-driven height below the cloud layer so the start isn't "mixed into the clouds",
        // but never below the build top + clearance (the aerial must still look DOWN on the build).
        double camY = Math.max(currentTop + clearance, Math.min(camYByPitch, cloudCeiling));
        return Orbit.poseAt(cx, cz, baseY, camRadius, camY, camY, frac, renderMs, buildMs);
    }

    /**
     * TRACKING DOLLY for a LINEAR build (a bridge/wall — one horizontal axis dominates): you can't fit a
     * long build in one frame, so the camera flies ALONGSIDE its long axis at a fixed lateral+vertical
     * offset, aiming at the build centerline at its current sweep point, dollying end→end as
     * {@code frac} goes 0→1. Pure. The lateral distance reuses the V2-1 FOV math on the CROSS-SECTION
     * (height × short-width) so the build fills the frame while the camera travels its length.
     *
     * @param frac          progress in [0,1] = position along the long axis (start → end)
     * @param camHeightFrac camera height as a fraction of the build height (aim is at mid-height)
     */
    public static OrbitKeyframe trackingPose(Bounds fullBounds, double frac,
                                             int aspectW, int aspectH, double baseFovDeg,
                                             double headroomFrac, double margin, double camHeightFrac,
                                             long renderMs, long buildMs) {
        boolean longX = fullBounds.sizeX() >= fullBounds.sizeZ();
        double baseY = fullBounds.minY();
        double span = Math.max(1.0, fullBounds.sizeY());
        double crossHalf = Math.max(1.0, (longX ? fullBounds.sizeZ() : fullBounds.sizeX()) / 2.0);

        double tanV = Math.tan(Math.toRadians(baseFovDeg) / 2.0);
        double tanH = ((double) aspectW / aspectH) * tanV;
        double dV = (span * 0.5 + headroomFrac * span) / tanV; // fit the cross-section HEIGHT
        double dW = crossHalf / tanH;                          // fit the cross-section WIDTH
        double lateral = Math.max(4.0, Math.max(dV, dW) * margin);

        double f = Math.max(0.0, Math.min(1.0, frac));
        double longMin = longX ? fullBounds.minX() : fullBounds.minZ();
        double longMax = (longX ? fullBounds.maxX() : fullBounds.maxZ()) + 1.0;
        double sweep = longMin + f * (longMax - longMin);
        double crossCenter = longX ? (fullBounds.minZ() + fullBounds.maxZ() + 1) / 2.0
                : (fullBounds.minX() + fullBounds.maxX() + 1) / 2.0;

        double camY = baseY + camHeightFrac * span;
        double aimY = baseY + 0.5 * span;
        double camX = longX ? sweep : crossCenter + lateral;
        double camZ = longX ? crossCenter + lateral : sweep;
        double aimX = longX ? sweep : crossCenter;
        double aimZ = longX ? crossCenter : sweep;

        float[] look = Orbit.lookAt(camX, camY, camZ, aimX, aimY, aimZ);
        long renderT = Math.round(renderMs * f);
        long replayT = Math.round(buildMs * f);
        return new OrbitKeyframe(camX, camY, camZ, look[0], look[1], 0f, renderT, replayT);
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

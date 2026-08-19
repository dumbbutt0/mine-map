package com.map2mc.film;

import com.map2mc.orbit.Orbit;
import com.map2mc.orbit.OrbitKeyframe;
import com.map2mc.place.Bounds;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the director pose (mirrors OrbitTest): for any progress the camera lies on
 * the orbit circle, its look-vector points at the frontier aim, the pose is continuous in progress,
 * and the camera height tracks the (rising) frontier. No Minecraft bootstrap.
 */
class FilmDirectorTest {

    private static final double EPS = 1e-6;
    private static final double CX = 10, CZ = -5, RADIUS = 30;
    private static final double RISE = 10, LEAD = 2;
    private static final Orbit.Params PARAMS = new Orbit.Params(CX, CZ, 0, RADIUS, 0, 0);
    private static final Bounds DUMMY = new Bounds(-10, -60, -10, 30, 160, 30);

    /** A frontier whose centroid sits at the build centre but at a given height. */
    private static Frontier frontierAtY(double y) {
        return new Frontier(new Vec3d(CX, y, CZ), DUMMY, 100);
    }

    @Test
    void poseSitsOnTheOrbitCircle() {
        for (int i = 0; i <= 40; i++) {
            float progress = i / 40f;
            double fy = -60 + progress * 200; // rising front
            OrbitKeyframe k = FilmDirector.directorPose(progress, frontierAtY(fy), PARAMS, 1, RISE, LEAD, 0, 0);
            assertEquals(RADIUS, Math.hypot(k.x() - CX, k.z() - CZ), EPS, "off the orbit circle");
        }
    }

    @Test
    void lookVectorPointsAtTheFrontierAim() {
        for (int i = 0; i <= 40; i++) {
            float progress = i / 40f;
            double fy = -60 + progress * 200;
            OrbitKeyframe k = FilmDirector.directorPose(progress, frontierAtY(fy), PARAMS, 1, RISE, LEAD, 0, 0);
            // forward vector implied by (yaw, pitch)
            double yaw = Math.toRadians(k.yaw()), pitch = Math.toRadians(k.pitch());
            double fx = -Math.sin(yaw) * Math.cos(pitch);
            double fyv = -Math.sin(pitch);
            double fz = Math.cos(yaw) * Math.cos(pitch);
            // must point at (CX, fy+LEAD, CZ) — the frontier, NOT the orbit centre at some fixed Y
            double dx = CX - k.x(), dy = (fy + LEAD) - k.y(), dz = CZ - k.z();
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(dx / len, fx, EPS, "aim x");
            assertEquals(dy / len, fyv, EPS, "aim y (tracks frontier height)");
            assertEquals(dz / len, fz, EPS, "aim z");
        }
    }

    @Test
    void cameraHeightTracksFrontierAndIsMonotonic() {
        double prevY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= 40; i++) {
            float progress = i / 40f;
            double fy = -60 + progress * 200; // monotonically rising
            OrbitKeyframe k = FilmDirector.directorPose(progress, frontierAtY(fy), PARAMS, 1, RISE, LEAD, 0, 0);
            assertEquals(fy + RISE, k.y(), EPS, "camera sits RISE above the frontier");
            assertTrue(k.y() >= fy, "camera stays above the front");
            assertTrue(k.y() >= prevY, "camera height non-decreasing as the front rises");
            prevY = k.y();
        }
    }

    @Test
    void framingRadiusGrowsWithBuiltHeight() {
        // Eiffel-ish footprint 86×85, base at y=-60.
        Bounds full = new Bounds(0, -60, 0, 85, 162, 84);
        double rLow = FilmDirector.framingFor(full, -55, 0, 2.0).radius();  // just above the base
        double rMid = FilmDirector.framingFor(full, 50, 0, 2.0).radius();
        double rHigh = FilmDirector.framingFor(full, 160, 0, 2.0).radius(); // near the top
        assertTrue(rLow < rMid && rMid < rHigh,
                "radius must grow as the build gets taller: " + rLow + " < " + rMid + " < " + rHigh);
        // orbit centre is the full footprint centre — constant regardless of built height
        assertEquals(FilmDirector.framingFor(full, 0, 0, 2.0).cx(),
                FilmDirector.framingFor(full, 160, 0, 2.0).cx(), EPS);
    }

    @Test
    void framingRadiusOverridePins() {
        Bounds full = new Bounds(0, -60, 0, 85, 162, 84);
        assertEquals(99.0, FilmDirector.framingFor(full, -55, 99, 2.0).radius(), EPS);
        assertEquals(99.0, FilmDirector.framingFor(full, 160, 99, 2.0).radius(), EPS, "override pins radius");
    }

    // ── V2-1: aspect-aware vertical + hero framing ───────────────────────────────────────────────

    @Test
    void aspectVerticalFramesByHeight() {
        // 9:16 portrait, a tall thin tower (footprint 86×85, height ~222) → HEIGHT binds.
        Bounds full = new Bounds(0, -60, 0, 85, 162, 84);
        int w = 9, h = 16;
        double fov = 70.0, head = 0.15, margin = 1.10;
        double r = FilmDirector.framingForAspect(full, 162, 0, w, h, fov, head, margin, 0.35, 0.85).radius();

        double span = 162 - (-60);
        double tanV = Math.tan(Math.toRadians(fov) / 2.0);
        double tanH = ((double) w / h) * tanV;
        double dV = (span * 0.5 + head * span) / tanV;
        double dW = full.horizontalRadius() / tanH;
        assertTrue(dV > dW, "height should bind for a tall tower in 9:16: dV=" + dV + " dW=" + dW);
        assertEquals(Math.max(dV, dW) * margin, r, 1e-3, "radius = height-bound distance × margin");

        double rNoHead = FilmDirector.framingForAspect(full, 162, 0, w, h, fov, 0.0, margin, 0.35, 0.85).radius();
        assertTrue(r > rNoHead, "headroom must increase the framing distance");
    }

    @Test
    void aspectLandscapeFramesByWidth() {
        // 16:9 landscape, a wide flat build (footprint 200×200, height ~19) → WIDTH binds.
        Bounds wide = new Bounds(0, 0, 0, 199, 19, 199);
        int w = 16, h = 9;
        double fov = 70.0, head = 0.15, margin = 1.10;
        double r = FilmDirector.framingForAspect(wide, 19, 0, w, h, fov, head, margin, 0.35, 0.85).radius();

        double span = 19.0;
        double tanV = Math.tan(Math.toRadians(fov) / 2.0);
        double tanH = ((double) w / h) * tanV;
        double dV = (span * 0.5 + head * span) / tanV;
        double dW = wide.horizontalRadius() / tanH;
        assertTrue(dW > dV, "width should bind for a wide flat build in 16:9: dV=" + dV + " dW=" + dW);
        assertEquals(Math.max(dV, dW) * margin, r, 1e-3, "radius = width-bound distance × margin");
    }

    @Test
    void aspectRadiusGrowsWithBuiltHeight() {
        Bounds full = new Bounds(0, -60, 0, 85, 162, 84);
        double rLow = FilmDirector.framingForAspect(full, -40, 0, 9, 16, 70, 0.15, 1.10, 0.35, 0.85).radius();
        double rMid = FilmDirector.framingForAspect(full, 70, 0, 9, 16, 70, 0.15, 1.10, 0.35, 0.85).radius();
        double rHigh = FilmDirector.framingForAspect(full, 162, 0, 9, 16, 70, 0.15, 1.10, 0.35, 0.85).radius();
        assertTrue(rLow < rMid && rMid < rHigh,
                "9:16 framing distance must grow as the build climbs: " + rLow + " < " + rMid + " < " + rHigh);
    }

    @Test
    void heroLookIsUpward() {
        Bounds full = new Bounds(0, -60, 0, 85, 162, 84);
        Orbit.Params p = FilmDirector.framingForAspect(full, 162, 0, 9, 16, 70, 0.15, 1.10, 0.35, 0.85);
        assertTrue(p.aimY() > p.camYStart(), "aim should be above the camera (so it looks up)");
        OrbitKeyframe k = Orbit.poseAt(p.cx(), p.cz(), p.aimY(), p.radius(),
                p.camYStart(), p.camYEnd(), 0.0, 0, 0);
        assertTrue(k.pitch() < 0, "hero shot looks UP (negative pitch), got " + k.pitch());
    }

    @Test
    void aspectRadiusOverridePins() {
        Bounds full = new Bounds(0, -60, 0, 85, 162, 84);
        assertEquals(77.0,
                FilmDirector.framingForAspect(full, 50, 77, 9, 16, 70, 0.15, 1.10, 0.35, 0.85).radius(), EPS);
    }

    // ── Aerial establishing shot (low-height phase) ──────────────────────────────────────────────

    @Test
    void aerialLooksDownFromAboveTheBuild() {
        // Eiffel footprint, early/low build (top = base+20); landscape 16:9 (the recording aspect).
        Bounds full = new Bounds(0, -60, 0, 85, 162, 84);
        int w = 16, h = 9;
        double fov = 70, pitch = 55, margin = 1.10, clearance = 8, currentTop = -40;
        OrbitKeyframe k = FilmDirector.aerialPose(full, 0.0, w, h, fov, pitch, margin, currentTop, clearance, 10_000, 0, 0);

        double cx = 43, cz = 42.5; // footprint centre
        double tanH = ((double) w / h) * Math.tan(Math.toRadians(fov) / 2.0);
        double camRadius = full.horizontalRadius() / tanH * margin;

        assertEquals(camRadius, Math.hypot(k.x() - cx, k.z() - cz), 1e-3, "framed to the footprint width");
        assertTrue(k.y() > currentTop, "aerial camera sits above the current build top");
        assertTrue(k.pitch() > 0, "aerial shot looks DOWN (positive pitch), got " + k.pitch());
        assertEquals(pitch, k.pitch(), 0.5, "look elevation ≈ filmAerialPitchDeg");
    }

    @Test
    void aerialClampKeepsCameraAboveATallLowPhaseBuild() {
        // Narrow footprint where width-framing alone would sit below a tall-ish low-phase build:
        // the clearance clamp must still put the camera above the current top.
        Bounds full = new Bounds(0, 0, 0, 19, 200, 19);
        double currentTop = 60, clearance = 8;
        OrbitKeyframe k = FilmDirector.aerialPose(full, 0.0, 16, 9, 70, 55, 1.10, currentTop, clearance, 10_000, 0, 0);
        assertTrue(k.y() >= currentTop + clearance - 1e-6, "clamp keeps the camera above the build top");
        assertTrue(k.pitch() > 0, "still looks down");
    }

    @Test
    void aerialCappedBelowCloudCeiling() {
        // A wide footprint whose pitch-driven height would punch through the clouds; the cap holds it
        // below the ceiling while still above the (low-phase) build top.
        Bounds full = new Bounds(0, 0, 0, 200, 100, 200);
        double currentTop = 10, clearance = 8, cloud = 80;
        OrbitKeyframe k = FilmDirector.aerialPose(full, 0.0, 16, 9, 70, 55, 1.10, currentTop, clearance, cloud, 0, 0);
        assertTrue(k.y() <= cloud + 1e-6, "aerial camera capped below the cloud ceiling, got " + k.y());
        assertTrue(k.y() >= currentTop + clearance - 1e-6, "but still above the build top");
        assertTrue(k.pitch() > 0, "still looks down");
    }

    @Test
    void aerialAzimuthRotatesWithFrac() {
        Bounds full = new Bounds(0, -60, 0, 85, 162, 84);
        OrbitKeyframe a = FilmDirector.aerialPose(full, 0.0, 16, 9, 70, 55, 1.10, -40, 8, 10_000, 0, 0);
        OrbitKeyframe b = FilmDirector.aerialPose(full, 0.25, 16, 9, 70, 55, 1.10, -40, 8, 10_000, 0, 0);
        double cx = 43, cz = 42.5;
        assertEquals(Math.hypot(a.x() - cx, a.z() - cz), Math.hypot(b.x() - cx, b.z() - cz), 1e-3,
                "same framing radius");
        assertTrue(Math.hypot(a.x() - b.x(), a.z() - b.z()) > 1.0, "azimuth advanced with frac");
    }

    @Test
    void aerialPoseHasNoRoll() {
        // The canted overhead horizon is steep-pitch-pan geometry, NOT camera roll — guarantee roll=0
        // so nobody "fixes" the cant by introducing actual roll.
        Bounds full = new Bounds(0, -60, 0, 85, 162, 84);
        for (double frac = 0.0; frac <= 1.0; frac += 0.2) {
            OrbitKeyframe k = FilmDirector.aerialPose(full, frac, 16, 9, 70, 50, 1.10, -40, 8, 10_000, 0, 0);
            assertEquals(0f, k.roll(), 0f, "aerial pose carries no roll");
        }
    }

    @Test
    void poseIsContinuousInProgress() {
        OrbitKeyframe prev = null;
        for (int i = 0; i <= 1000; i++) {
            float progress = i / 1000f;
            double fy = -60 + progress * 200;
            OrbitKeyframe k = FilmDirector.directorPose(progress, frontierAtY(fy), PARAMS, 1, RISE, LEAD, 0, 0);
            if (prev != null) {
                double step = Math.sqrt(Math.pow(k.x() - prev.x(), 2) + Math.pow(k.y() - prev.y(), 2)
                        + Math.pow(k.z() - prev.z(), 2));
                // one revolution over 1000 samples on r=30 → arc per step ≈ 2π·30/1000 ≈ 0.19; allow margin
                assertTrue(step < 1.0, "pose jumped between adjacent progress samples: " + step);
            }
            prev = k;
        }
    }

    // ── trackingPose: the LINEAR (bridge/wall) dolly ────────────────────────────────────────────

    /** A long linear build along X: 200 long, 8 wide, 20 tall, based at y=64. */
    private static final Bounds LINEAR = new Bounds(0, 64, 0, 199, 83, 7);

    @Test
    void trackingDollySweepsTheLongAxisEndToEnd() {
        OrbitKeyframe start = FilmDirector.trackingPose(LINEAR, 0.0, 9, 16, 70, 0.15, 1.0, 0.4, 0, 0);
        OrbitKeyframe end = FilmDirector.trackingPose(LINEAR, 1.0, 9, 16, 70, 0.15, 1.0, 0.4, 0, 0);
        // long axis is X here; the camera's X should travel from the min end to the max end
        assertTrue(start.x() < end.x(), "camera dollies along the long axis");
        assertTrue(start.x() <= 5 && end.x() >= 195, "sweep spans essentially the whole length");
    }

    @Test
    void trackingDollyRunsAlongsideAndAimsAtTheCenterline() {
        double cz = (LINEAR.minZ() + LINEAR.maxZ() + 1) / 2.0; // build centerline Z (=4)
        for (double f = 0.0; f <= 1.0; f += 0.1) {
            OrbitKeyframe k = FilmDirector.trackingPose(LINEAR, f, 9, 16, 70, 0.15, 1.0, 0.4, 0, 0);
            // camera sits to one side (lateral offset on Z), not on the centerline
            assertTrue(Math.abs(k.z() - cz) > LINEAR.sizeZ() / 2.0, "camera is offset to the side of the build");
            // yaw points toward the build (its centerline is at smaller Z than the camera → look -Z-ish);
            // reconstruct the forward vector and check it has a component toward the build in Z
            double yaw = Math.toRadians(k.yaw());
            double fz = Math.cos(yaw) * Math.cos(Math.toRadians(k.pitch()));
            assertTrue((cz - k.z()) * fz > 0, "camera looks toward the build centerline");
        }
    }
}

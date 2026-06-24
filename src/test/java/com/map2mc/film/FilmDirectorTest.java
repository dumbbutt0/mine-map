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
}

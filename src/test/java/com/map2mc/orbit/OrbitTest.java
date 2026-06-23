package com.map2mc.orbit;

import com.map2mc.place.Bounds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for the orbit geometry: on-circle positions + look-at-center angles. */
class OrbitTest {

    private static final double EPS = 1e-6;

    @Test
    void everyKeyframeSitsOnTheRequestedCircle() {
        double cx = 10, cz = -4, radius = 25;
        List<OrbitKeyframe> kfs = Orbit.compute(cx, cz, 30, radius, 20, 40, 8, 20000, 120000);
        assertEquals(8, kfs.size());
        for (OrbitKeyframe k : kfs) {
            double horiz = Math.hypot(k.x() - cx, k.z() - cz);
            assertEquals(radius, horiz, 1e-6, "keyframe not on circle");
        }
    }

    @Test
    void everyKeyframeLooksAtTheCenter() {
        double cx = 0, cz = 0, aimY = 10, radius = 20;
        List<OrbitKeyframe> kfs = Orbit.compute(cx, cz, aimY, radius, 5, 25, 12, 20000, 120000);
        for (OrbitKeyframe k : kfs) {
            // Forward vector implied by (yaw, pitch) must point from the camera at the center.
            double yaw = Math.toRadians(k.yaw());
            double pitch = Math.toRadians(k.pitch());
            double fx = -Math.sin(yaw) * Math.cos(pitch);
            double fy = -Math.sin(pitch);
            double fz = Math.cos(yaw) * Math.cos(pitch);
            // Direction from the camera to the center, normalized.
            double dx = cx - k.x(), dy = aimY - k.y(), dz = cz - k.z();
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(dx / len, fx, EPS, "yaw/pitch not aimed at center (x)");
            assertEquals(dy / len, fy, EPS, "yaw/pitch not aimed at center (y)");
            assertEquals(dz / len, fz, EPS, "yaw/pitch not aimed at center (z)");
        }
    }

    @Test
    void heightRisesGentlyAndTimesMapMonotonically() {
        List<OrbitKeyframe> kfs = Orbit.compute(0, 0, 10, 20, 5, 25, 8, 20000, 120000);
        assertEquals(5, kfs.get(0).y(), EPS);
        assertEquals(25, kfs.get(kfs.size() - 1).y(), EPS);
        assertEquals(0, kfs.get(0).renderTimeMs());
        assertEquals(20000, kfs.get(kfs.size() - 1).renderTimeMs());
        assertEquals(120000, kfs.get(kfs.size() - 1).replayTimeMs());
        for (int i = 1; i < kfs.size(); i++) {
            assertTrue(kfs.get(i).y() >= kfs.get(i - 1).y(), "height must not drop");
            assertTrue(kfs.get(i).renderTimeMs() > kfs.get(i - 1).renderTimeMs(), "time monotonic");
        }
    }

    @Test
    void paramsDeriveRadiusFromBuildSizeOrHonorOverride() {
        Bounds b = new Bounds(0, -60, 0, 19, 100, 9); // 20 x 161 x 10
        Orbit.Params auto = Orbit.Params.fromBounds(b, 0, 2.0);
        // horizontalRadius = max(20,10)/2 = 10 -> 10*2 + span*0.25; clearly > the build half-extent.
        assertTrue(auto.radius() > 10, "auto radius should clear the build");
        assertEquals(10.0, auto.cx(), EPS);   // (0+19+1)/2
        assertEquals(5.0, auto.cz(), EPS);    // (0+9+1)/2
        Orbit.Params pinned = Orbit.Params.fromBounds(b, 42, 2.0);
        assertEquals(42.0, pinned.radius(), EPS);
    }
}

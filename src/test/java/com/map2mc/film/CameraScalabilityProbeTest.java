package com.map2mc.film;

import com.map2mc.Map2mcConfig;
import com.map2mc.film.StructureProfile.AspectClass;
import com.map2mc.orbit.Orbit;
import com.map2mc.orbit.OrbitKeyframe;
import com.map2mc.place.Bounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless camera stress test: runs the EXISTING shot-decision path (the same pure functions, the same
 * aerial→orbit gate, and now the same per-class branch that {@link DirectorCamera#tickFilm} uses) across
 * four structure archetypes, and prints a per-archetype shot-decision table. It proved the original
 * shape-blindness (CAMERA_SCALABILITY.md); it now also proves the fix landed for LINEAR (a tracking
 * dolly instead of the 14% aerial collapse) while documenting the classes still on the roadmap.
 *
 * <p>Replicates the decision rather than calling {@code tickFilm} (which needs a live build/Frontier):
 * {@code currentTop} is the monotone proxy {@code baseY + progress·height}.
 */
class CameraScalabilityProbeTest {

    private static final double AERIAL_CLEARANCE = 8.0; // mirrors DirectorCamera.AERIAL_CLEARANCE
    private static final double STEP = 0.05;

    private record Archetype(String name, Bounds bounds, int[] area) {
    }

    private record Result(String name, AspectClass cls, String shotType, double footprintRadius,
                          double handoff, double aerialFrac, double orbitRadMin, double orbitRadMax,
                          double camYMin, double camYMax, String verdict) {
    }

    @Test
    void probeArchetypesAndPrintTable() {
        Archetype[] archetypes = {
                new Archetype("TALL_TOWER  (20x220x20, narrowing)", new Bounds(0, 64, 0, 19, 283, 19),
                        narrowing(220, 30, 400, 60)),
                new Archetype("WIDE_LOW    (120x30x120, uniform)", new Bounds(0, 64, 0, 119, 93, 119),
                        uniform(30, 14400)),
                new Archetype("LINEAR      (200x20x8, uniform)", new Bounds(0, 64, 0, 199, 83, 7),
                        uniform(20, 1600)),
                new Archetype("TALL_STATUE (30x60x30, uniform)", new Bounds(0, 64, 0, 29, 123, 29),
                        uniform(60, 900)),
        };

        StringBuilder out = new StringBuilder();
        out.append("\n=== map2mc camera scalability probe ===\n");
        out.append(String.format("config: aspect=%d:%d fov=%.0f structureClass=%s aerialUntilHeightFrac=%.2f "
                        + "transitionFrac=%.2f frameMargin=%.2f%n",
                Map2mcConfig.filmAspectW, Map2mcConfig.filmAspectH, Map2mcConfig.filmBaseFovDeg,
                Map2mcConfig.filmStructureClass, Map2mcConfig.filmAerialUntilHeightFrac,
                Map2mcConfig.filmTransitionFrac, Map2mcConfig.filmFrameMargin));
        out.append(String.format("%-34s %-18s %-13s %7s %8s %8s %16s  %s%n",
                "archetype", "class", "shot", "fpRad", "handoff", "aerial%", "orbitR(min..max)", "verdict"));

        Result[] r = new Result[archetypes.length];
        for (int i = 0; i < archetypes.length; i++) {
            r[i] = probe(archetypes[i]);
            out.append(String.format("%-34s %-18s %-13s %7.1f %8.2f %7.0f%% %7.0f..%-6.0f  %s%n",
                    r[i].name(), r[i].cls(), r[i].shotType(), r[i].footprintRadius(), r[i].handoff(),
                    r[i].aerialFrac() * 100, r[i].orbitRadMin(), r[i].orbitRadMax(), r[i].verdict()));
        }
        System.out.println(out);

        Result tower = r[0], wide = r[1], linear = r[2], statue = r[3];

        // Classification is correct per archetype.
        assertEquals(AspectClass.NARROWING_VERTICAL, tower.cls());
        assertEquals(AspectClass.WIDE_LOW, wide.cls());
        assertEquals(AspectClass.LINEAR, linear.cls());
        assertEquals(AspectClass.TALL, statue.cls());

        // FIX LANDED: LINEAR now gets a TRACKING dolly, distinct from the tower's aerial→orbit — the
        // shape-blind 14% collapse is broken for linear builds (the whole point of this round).
        assertEquals("TRACKING", linear.shotType());
        assertNotEquals(tower.shotType(), linear.shotType(), "linear no longer shares the tower's shot");

        // The tower path is unchanged: structural handoff + both phases + the far-camera reality.
        assertTrue(tower.handoff() >= 0.0 && tower.aerialFrac() > 0 && tower.aerialFrac() < 1.0);
        assertTrue(tower.orbitRadMax() > 10 * tower.footprintRadius(), "tall build in 9:16 sits far back");

        // STILL ON THE ROADMAP: the OTHER uniform classes (wide, tall-statue) remain shape-blind — same
        // aerial fraction, no dedicated shot yet. Keep this visible so the next steps don't get forgotten.
        assertEquals(-1.0, wide.handoff(), 1e-9);
        assertEquals(-1.0, statue.handoff(), 1e-9);
        assertEquals(wide.aerialFrac(), statue.aerialFrac(), 1e-9,
                "wide vs tall-statue still collapse to the same split (WIDE/SQUAT/TALL shots not built yet)");
    }

    private Result probe(Archetype a) {
        Bounds b = a.bounds();
        double cx = (b.minX() + b.maxX() + 1) / 2.0;
        double cz = (b.minZ() + b.maxZ() + 1) / 2.0;
        double baseY = b.minY();
        double finalHeight = Math.max(1.0, b.maxY() - baseY);
        double heightThreshold = Map2mcConfig.filmAerialUntilHeightFrac * finalHeight;
        double handoff = AreaProfile.handoffProgress(a.area(), Map2mcConfig.filmTransitionFrac);
        AspectClass cls = StructureProfile.classify(b, a.area(), Map2mcConfig.filmTransitionFrac).aspectClass();
        double footprintRadius = b.horizontalRadius();

        double camYMin = Double.POSITIVE_INFINITY, camYMax = Double.NEGATIVE_INFINITY;
        double radMin = Double.POSITIVE_INFINITY, radMax = 0;

        // LINEAR → the new tracking-dolly branch (mirrors DirectorCamera.tickFilm): one sweep, no aerial.
        if (cls == AspectClass.LINEAR) {
            for (double p = 0.0; p <= 1.0001; p += STEP) {
                OrbitKeyframe k = FilmDirector.trackingPose(b, Math.min(1.0, p),
                        Map2mcConfig.filmAspectW, Map2mcConfig.filmAspectH, Map2mcConfig.filmBaseFovDeg,
                        Map2mcConfig.filmHeadroomFrac, Map2mcConfig.filmFrameMargin,
                        Map2mcConfig.filmHeroCamFrac, 0L, 60000L);
                camYMin = Math.min(camYMin, k.y());
                camYMax = Math.max(camYMax, k.y());
                double rad = Math.hypot(k.x() - cx, k.z() - cz);
                radMin = Math.min(radMin, rad);
                radMax = Math.max(radMax, rad);
            }
            return new Result(a.name(), cls, "TRACKING", footprintRadius, handoff, 0.0, radMin, radMax,
                    camYMin, camYMax, "tracking dolly along the long axis (was a wrong orbit before)");
        }

        // Otherwise the existing aerial→orbit gate.
        int aerialSteps = 0, total = 0;
        for (double p = 0.0; p <= 1.0001; p += STEP) {
            double progress = Math.min(1.0, p);
            double clampedTop = baseY + progress * finalHeight;
            double currentHeight = clampedTop - baseY;
            boolean structuralPassed = handoff < 0 || progress >= handoff;
            boolean heightReached = currentHeight >= heightThreshold;
            boolean aerial = Map2mcConfig.filmAerialEnabled && !(structuralPassed && heightReached);
            double camY;
            if (aerial) {
                double aerialFrac = Map2mcConfig.filmAerialTurns
                        * Math.min(1.0, Math.max(0.0, currentHeight / Math.max(1.0, heightThreshold)));
                OrbitKeyframe k = FilmDirector.aerialPose(b, aerialFrac,
                        Map2mcConfig.filmAspectW, Map2mcConfig.filmAspectH, Map2mcConfig.filmBaseFovDeg,
                        Map2mcConfig.filmAerialPitchDeg, Map2mcConfig.filmAerialMargin,
                        clampedTop, AERIAL_CLEARANCE, Map2mcConfig.filmCloudCeilingY, 0L, 60000L);
                camY = k.y();
                aerialSteps++;
            } else {
                Orbit.Params params = FilmDirector.framingForAspect(b, clampedTop, -1,
                        Map2mcConfig.filmAspectW, Map2mcConfig.filmAspectH, Map2mcConfig.filmBaseFovDeg,
                        Map2mcConfig.filmHeadroomFrac, Map2mcConfig.filmFrameMargin,
                        Map2mcConfig.filmHeroCamFrac, Map2mcConfig.filmHeroAimFrac);
                radMin = Math.min(radMin, params.radius());
                radMax = Math.max(radMax, params.radius());
                camY = params.camYStart();
            }
            camYMin = Math.min(camYMin, camY);
            camYMax = Math.max(camYMax, camY);
            total++;
        }
        double aerialFrac = (double) aerialSteps / total;
        if (radMin == Double.POSITIVE_INFINITY) {
            radMin = radMax = 0;
        }
        String shotType = aerialFrac > 0 ? "AERIAL+ORBIT" : "ORBIT";
        String verdict;
        if (radMax > 4 * footprintRadius) {
            verdict = "FAR (orbit ~" + Math.round(radMax / Math.max(1, footprintRadius)) + "x footprint)";
        } else {
            verdict = "shape-blind split (no dedicated shot yet)";
        }
        return new Result(a.name(), cls, shotType, footprintRadius, handoff, aerialFrac, radMin, radMax,
                camYMin, camYMax, verdict);
    }

    private static int[] narrowing(int height, int baseLayers, int baseArea, int bodyArea) {
        int[] a = new int[height];
        for (int y = 0; y < height; y++) {
            a[y] = y < baseLayers ? baseArea : Math.max(1, bodyArea - (y - baseLayers) / 4);
        }
        return a;
    }

    private static int[] uniform(int height, int area) {
        int[] a = new int[height];
        java.util.Arrays.fill(a, area);
        return a;
    }
}

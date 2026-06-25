package com.map2mc.film;

import com.map2mc.Map2mcConfig;
import com.map2mc.orbit.Orbit;
import com.map2mc.orbit.OrbitKeyframe;
import com.map2mc.place.Bounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless camera stress test: runs the EXISTING shot-decision path (the same pure functions and the
 * same aerial→orbit gate that {@link DirectorCamera#tickFilm} uses) across four structure archetypes,
 * and prints a per-archetype shot-decision table. The point is to PROVE where the camera — tuned to the
 * tall, narrowing Eiffel — breaks on other shapes, before generalizing it. The printed table is the
 * source for CAMERA_SCALABILITY.md. A couple of assertions pin the known-good tower and the documented
 * couplings so the findings can't silently drift.
 *
 * <p>This replicates the gate rather than calling {@code tickFilm} (which needs a live build/Frontier):
 * {@code currentTop} is the monotone proxy {@code baseY + progress·height} — fair for a shot-shape probe.
 */
class CameraScalabilityProbeTest {

    private static final double AERIAL_CLEARANCE = 8.0; // mirrors DirectorCamera.AERIAL_CLEARANCE
    private static final double STEP = 0.05;

    private record Archetype(String name, Bounds bounds, int[] area) {
    }

    /** One archetype's measured behavior across the whole 0→1 timeline. */
    private record Result(String name, double footprintRadius, double handoff, double aerialFrac,
                          double orbitRadMin, double orbitRadMax, double camYMin, double camYMax,
                          String verdict) {
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
                new Archetype("SQUAT_STATUE(30x60x30, uniform)", new Bounds(0, 64, 0, 29, 123, 29),
                        uniform(60, 900)),
        };

        StringBuilder out = new StringBuilder();
        out.append("\n=== map2mc camera scalability probe ===\n");
        out.append(String.format("config: aspect=%d:%d fov=%.0f aerialEnabled=%b aerialUntilHeightFrac=%.2f "
                        + "transitionFrac=%.2f frameMargin=%.2f heroCamFrac=%.2f%n",
                Map2mcConfig.filmAspectW, Map2mcConfig.filmAspectH, Map2mcConfig.filmBaseFovDeg,
                Map2mcConfig.filmAerialEnabled, Map2mcConfig.filmAerialUntilHeightFrac,
                Map2mcConfig.filmTransitionFrac, Map2mcConfig.filmFrameMargin, Map2mcConfig.filmHeroCamFrac));
        out.append(String.format("%-34s %8s %8s %9s %16s %14s  %s%n",
                "archetype", "fpRad", "handoff", "aerial%", "orbitR(min..max)", "camY(min..max)", "verdict"));

        Result[] results = new Result[archetypes.length];
        for (int i = 0; i < archetypes.length; i++) {
            results[i] = probe(archetypes[i]);
            Result r = results[i];
            out.append(String.format("%-34s %8.1f %8.2f %8.0f%% %7.0f..%-6.0f %6.0f..%-6.0f  %s%n",
                    r.name(), r.footprintRadius(), r.handoff(), r.aerialFrac() * 100,
                    r.orbitRadMin(), r.orbitRadMax(), r.camYMin(), r.camYMax(), r.verdict()));
        }
        out.append("(verdict: OK = sane; FAR = orbit radius ≫ footprint; ALL-AERIAL / NO-AERIAL = "
                + "degenerate phase split)\n");
        System.out.println(out);

        // --- Findings pinned as assertions (encode what the probe actually measured) ---
        Result tower = results[0], wide = results[1], linear = results[2], squat = results[3];

        // FINDING 1 — only a NARROWING build gets a real structural handoff; the aerial→orbit cut is
        // then driven by structure (tower handoff≈0.63). Uniform shapes get handoff=-1 every time.
        assertTrue(tower.handoff() >= 0.0, "TALL_TOWER narrows → structural handoff detected");
        assertEquals(-1.0, wide.handoff(), 1e-9, "WIDE_LOW uniform → NO structural handoff");
        assertEquals(-1.0, linear.handoff(), 1e-9, "LINEAR uniform → NO structural handoff");
        assertEquals(-1.0, squat.handoff(), 1e-9, "SQUAT uniform → NO structural handoff");

        // FINDING 2 — the phase split is SHAPE-BLIND: every uniform build (wildly different footprints:
        // 120x120, 200x8, 30x30) collapses to the SAME aerial fraction, because the gate only looks at
        // height-progress, never at footprint shape. That's the core non-scalability.
        assertEquals(wide.aerialFrac(), linear.aerialFrac(), 1e-9,
                "wide vs linear get an IDENTICAL phase split despite opposite shapes (shape-blind gate)");
        assertEquals(wide.aerialFrac(), squat.aerialFrac(), 1e-9, "squat too — purely progress-driven");
        assertTrue(tower.aerialFrac() > wide.aerialFrac(),
                "the narrowing tower gets MORE establishing time than the height-fallback uniform builds");

        // FINDING 3 — fitting a tall build in a 9:16 frame pushes the orbit camera many× its footprint
        // away (the confirmed 'background dominates / distant chunks' problem), even for the tuned tower.
        assertTrue(tower.orbitRadMax() > 10 * tower.footprintRadius(),
                "TALL_TOWER orbit camera sits >10x its footprint away (huge background to render)");
    }

    private Result probe(Archetype a) {
        Bounds b = a.bounds();
        double cx = (b.minX() + b.maxX() + 1) / 2.0;
        double cz = (b.minZ() + b.maxZ() + 1) / 2.0;
        double baseY = b.minY();
        double finalHeight = Math.max(1.0, b.maxY() - baseY);
        double heightThreshold = Map2mcConfig.filmAerialUntilHeightFrac * finalHeight;
        double handoff = AreaProfile.handoffProgress(a.area(), Map2mcConfig.filmTransitionFrac);
        double footprintRadius = b.horizontalRadius();

        int aerialSteps = 0;
        int total = 0;
        double orbitRadMin = Double.POSITIVE_INFINITY, orbitRadMax = 0;
        double camYMin = Double.POSITIVE_INFINITY, camYMax = Double.NEGATIVE_INFINITY;

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
                orbitRadMin = Math.min(orbitRadMin, params.radius());
                orbitRadMax = Math.max(orbitRadMax, params.radius());
                camY = params.camYStart();
            }
            camYMin = Math.min(camYMin, camY);
            camYMax = Math.max(camYMax, camY);
            total++;
        }

        double aerialFrac = (double) aerialSteps / total;
        if (orbitRadMin == Double.POSITIVE_INFINITY) {
            orbitRadMin = orbitRadMax = 0; // never reached the orbit phase
        }
        String verdict;
        if (aerialFrac >= 1.0) {
            verdict = "ALL-AERIAL (never orbits — wrong for a build with height)";
        } else if (aerialFrac <= 0.0) {
            verdict = "NO-AERIAL (no establishing shot)";
        } else if (orbitRadMax > 4 * footprintRadius) {
            verdict = "FAR (orbit camera ~" + Math.round(orbitRadMax / Math.max(1, footprintRadius))
                    + "x footprint away — build tiny, background dominates)";
        } else {
            verdict = "OK";
        }
        return new Result(a.name(), footprintRadius, handoff, aerialFrac,
                orbitRadMin, orbitRadMax, camYMin, camYMax, verdict);
    }

    /** A narrowing tower profile: a wide base for {@code baseLayers}, then a narrow body. */
    private static int[] narrowing(int height, int baseLayers, int baseArea, int bodyArea) {
        int[] a = new int[height];
        for (int y = 0; y < height; y++) {
            a[y] = y < baseLayers ? baseArea : Math.max(1, bodyArea - (y - baseLayers) / 4);
        }
        return a;
    }

    /** A uniform profile (every layer the same area) — no base→vertical transition. */
    private static int[] uniform(int height, int area) {
        int[] a = new int[height];
        java.util.Arrays.fill(a, area);
        return a;
    }
}

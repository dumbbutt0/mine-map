package com.map2mc.film;

import com.map2mc.film.StructureProfile.AspectClass;
import com.map2mc.place.Bounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the shape classifier — each archetype lands in the expected class, override forces it. */
class StructureProfileTest {

    private static final double FRAC = 0.40;

    @Test
    void classifiesTheArchetypes() {
        assertEquals(AspectClass.NARROWING_VERTICAL,
                StructureProfile.classify(new Bounds(0, 64, 0, 19, 283, 19), narrowing(220, 30, 400, 60), FRAC).aspectClass(),
                "tall + narrowing → tower");
        assertEquals(AspectClass.WIDE_LOW,
                StructureProfile.classify(new Bounds(0, 64, 0, 119, 93, 119), uniform(30, 14400), FRAC).aspectClass(),
                "much wider than tall → wide-low");
        assertEquals(AspectClass.LINEAR,
                StructureProfile.classify(new Bounds(0, 64, 0, 199, 83, 7), uniform(20, 1600), FRAC).aspectClass(),
                "one axis ≥3× the other → linear");
        assertEquals(AspectClass.TALL,
                StructureProfile.classify(new Bounds(0, 64, 0, 29, 123, 29), uniform(60, 900), FRAC).aspectClass(),
                "tall but uniform → tall (statue/pillar)");
        assertEquals(AspectClass.SQUAT,
                StructureProfile.classify(new Bounds(0, 64, 0, 39, 103, 39), uniform(40, 1600), FRAC).aspectClass(),
                "roughly cubic → squat");
    }

    @Test
    void overrideForcesTheClassButKeepsFeatures() {
        StructureProfile p = StructureProfile.resolve(
                new Bounds(0, 64, 0, 19, 283, 19), narrowing(220, 30, 400, 60), FRAC, "linear");
        assertEquals(AspectClass.LINEAR, p.aspectClass(), "override wins over the heuristic");
        assertTrue(p.reasoning().toLowerCase().contains("forced"), "reasoning notes it was forced");
        assertTrue(p.vertRatio() > 1.5, "raw features are still computed under an override");
    }

    @Test
    void reasoningShowsItsWork() {
        StructureProfile p = StructureProfile.classify(new Bounds(0, 64, 0, 19, 283, 19),
                narrowing(220, 30, 400, 60), FRAC);
        assertTrue(p.reasoning().contains("NARROWING_VERTICAL"), "label present");
        assertTrue(p.reasoning().contains("vertRatio") && p.reasoning().contains("handoff"),
                "the deciding features are in the reasoning (debuggable misclassifications)");
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

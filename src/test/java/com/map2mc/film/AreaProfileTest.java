package com.map2mc.film;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The area-profile handoff: cumulative-block progress at the base→vertical drop; -1 when ambiguous. */
class AreaProfileTest {

    @Test
    void handoffAtBaseToVerticalTransition() {
        // wide base (3 layers × 100) then a narrow column (10 layers × 4) — Eiffel-like.
        int[] area = new int[13];
        for (int y = 0; y < 3; y++) {
            area[y] = 100;
        }
        for (int y = 3; y < 13; y++) {
            area[y] = 4;
        }
        // handoffY = 3 (first < 0.4·100); blocks below = 300; total = 340.
        assertEquals(300.0 / 340.0, AreaProfile.handoffProgress(area, 0.4), 1e-6,
                "handoff at the base→column boundary");
    }

    @Test
    void handoffIsEarlyWhenTheBaseIsASmallFractionOfBlocks() {
        int[] area = new int[52];
        for (int y = 0; y < 2; y++) {
            area[y] = 50;   // base: 100 blocks
        }
        for (int y = 2; y < 52; y++) {
            area[y] = 4;    // tall thin body: 200 blocks
        }
        double p = AreaProfile.handoffProgress(area, 0.4);
        assertEquals(100.0 / 300.0, p, 1e-6);
        assertTrue(p < 0.5, "a tall thin body hands off EARLY, got " + p);
    }

    @Test
    void uniformBuildDefersToHeightFallback() {
        int[] tower = new int[20];
        Arrays.fill(tower, 16);
        int[] slab = new int[3];
        Arrays.fill(slab, 400);
        assertEquals(-1.0, AreaProfile.handoffProgress(tower, 0.4), 1e-9, "no transition → defer to height");
        assertEquals(-1.0, AreaProfile.handoffProgress(slab, 0.4), 1e-9);
    }

    @Test
    void emptyDefers() {
        assertEquals(-1.0, AreaProfile.handoffProgress(new int[]{0, 0, 0}, 0.4), 1e-9);
    }
}
